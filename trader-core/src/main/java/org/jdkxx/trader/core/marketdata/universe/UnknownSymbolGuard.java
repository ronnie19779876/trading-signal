package org.jdkxx.trader.core.marketdata.universe;

import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.UnknownSymbolException;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 批次级恢复：富途以「未知股票」拒掉整批时，找出坏代码、标 UNRESOLVED、让调用方剔除后重试一次，
 * 使一只坏代码只丢自己而不是整批。
 *
 * <p><b>为什么富途不认识一只已经解析过的代码</b>：代码会随公司行动改名。2026-10-06 派拉蒙天舞因
 * 1100 亿美元收购华纳兄弟探索交割，PSKY 改名 Skydance、代码变 SKYD、B 股交易所 Nasdaq→NYSE，
 * 富途随即不认 PSKY。而 {@link UniverseSyncService#resolveStatics} <b>只看非 RESOLVED 的标的</b>，
 * 所以 PSKY 自 2026-09-03 解析成功后就永久免检，仍以 RESOLVED 身份进采集批次，
 * 一次把增量的 90 批与估值的 400 批各毒掉一次（丢 90 只 K 线、400 条估值）。
 *
 * <h2>两层判定：绝不靠文本定生死</h2>
 * <ol>
 *   <li><b>触发</b>只看异常类型——券商<b>明确拒绝</b>（{@link RequestRejectedException}）。
 *       不看措辞：富途改一次文案不该让恢复失效。超时、断连一律不触发（见守护一）。</li>
 *   <li><b>判据</b>只认结构化事实——对整批调用 {@code staticInfo} 取 {@code brokerId==0}。
 *       这是 {@code resolveStatics} 已有的同一条判据，口径不分叉。</li>
 * </ol>
 * {@link UnknownSymbolException#named()} 只用来交叉核对、写日志，<b>不参与剔除</b>：
 * 2026-10-07 实测 {@code snapshots([PSKY, NOSUCHXYZ, AAPL])} 的 retMsg 里只有 PSKY，
 * 另一只坏代码没被提及——照文本剔除会漏掉它，下一批还是会被毒。
 *
 * <p>判据之所以能用，靠的是 {@code staticInfo} <b>在混批里免疫</b>：
 * 2026-10-07 对真实网关实测 {@code staticInfo([PSKY, AAPL, MSFT])} 成功回 3 条，
 * PSKY 那条是 {@code name=未知股票, brokerId=0, delisted=true}，AAPL/MSFT 正常；
 * 同一次运行里 {@code snapshots([PSKY, AAPL, MSFT])} 仍整批失败、{@code snapshots([AAPL, MSFT])} 成功
 * ——三个对照一起才证明这是接口差异，不是"富途已经重新认识 PSKY"。
 *
 * <h2>三道守护</h2>
 * <ol>
 *   <li><b>只对明确拒绝生效</b>。超时 / 断连 / 取消不触发。否则富途一次权限档位翻转
 *       （行情权限按登录 IP 在境内/国际档间翻转，已实测）就会把几百只标的集体标成 UNRESOLVED。</li>
 *   <li><b>一批里坏得太多就整批不动</b>（{@code max-unknown-per-batch}，默认 3）。依据：上线一个多月、
 *       500 多只标的，「RESOLVED → 富途不认识」真实发生 <b>1</b> 次。新进成分股走 PENDING，不走这条路。
 *       一批冒出 4 只以上更像富途侧出了事，不该由采集作业悄悄降级几百只标的。
 *       和成分股同步守护同一个哲学：<b>永不悄悄大批降级</b>，被挡一次正是该有人看一眼的时候。</li>
 *   <li><b>降级必须可见</b>——这条不在本类里，在审计的 {@code resolveDowngrade} 检查项。
 *       标成 UNRESOLVED 会让标的退出 {@link UniverseScope#usable} 的所有分母，于是完整性检查的
 *       分母跟着变小、<b>检查照样全绿</b>（和"成分股纯集合差看不见"是同一个坑）。
 *       所以必须有一条按<b>绝对数量</b>报的检查项盯住非 RESOLVED 的标的。</li>
 * </ol>
 *
 * <p>持仓侧传 {@code mayMark=false}：我们真正持有的标的改名，影响取价与实时订阅，
 * 值得人看一眼，所以只从本批剔除、不写库（作业记 PARTIAL）。
 */
public class UnknownSymbolGuard {

    private static final Logger log = LoggerFactory.getLogger(UnknownSymbolGuard.class);

    /**
     * @param exclude 核实为「富途不认识」、调用方应剔除后重试的代码；空表示守护拒绝介入，按原有行为处理
     * @param marked  本次真正写了库（标成 UNRESOLVED）的代码
     * @param detail  给日志与 {@code ctx.partial} 的说明，始终非空
     */
    public record Outcome(Set<String> exclude, Set<String> marked, String detail) {

        public Outcome {
            exclude = Set.copyOf(exclude);
            marked = Set.copyOf(marked);
        }

        public boolean canRetry() {
            return !exclude.isEmpty();
        }
    }

    private final MarketDataGateway gateway;
    private final InstrumentRepository instruments;
    private final int staticBatchSize;
    private final int maxUnknownPerBatch;

    public UnknownSymbolGuard(MarketDataGateway gateway, InstrumentRepository instruments,
                              int staticBatchSize, int maxUnknownPerBatch) {
        this.gateway = gateway;
        this.instruments = instruments;
        this.staticBatchSize = Math.max(1, staticBatchSize);
        this.maxUnknownPerBatch = Math.max(0, maxUnknownPerBatch);
    }

    /**
     * 批次调用失败后问一次：是不是有坏代码、该剔除哪些。
     *
     * @param batch    整批。<b>一只也介入</b>：反订阅一只富途已经不认识的代码会永久失败，
     *                 而把它从本地账本摘掉正是修法——所以不能按"只有一只就不查"来挡
     * @param failure  批次调用抛出的异常（允许是 {@code ExecutionException} 这类包装）
     * @param mayMark  是否允许写库。持仓与实时订阅侧传 false
     * @return 守护未介入时为空，调用方按原有行为处理
     */
    public Optional<Outcome> inspect(List<Instrument> batch, Throwable failure, boolean mayMark) {
        // 守护一：只对券商明确拒绝生效
        RequestRejectedException rejected = rejection(failure);
        if (rejected == null || batch == null || batch.isEmpty()) {
            return Optional.empty();
        }
        Set<String> named = rejected instanceof UnknownSymbolException u ? u.named() : Set.of();

        Set<String> unknown;
        try {
            unknown = findUnknown(batch);
        } catch (Exception e) {
            // 核实本身失败就什么都不做：没有判据不写库
            log.warn("核实未知代码失败，整批按原有方式处理：{}", e.toString());
            return Optional.of(new Outcome(Set.of(), Set.of(), "核实未知代码失败：" + e));
        }

        if (unknown.isEmpty()) {
            String detail = named.isEmpty()
                    ? "富途拒绝了整批，但逐只核实都认识——不是未知代码问题"
                    : "富途点名 " + named + "，但逐只核实都认识——不按未知代码处理";
            log.warn("{}（{} 只）：{}", "批次被拒", batch.size(), detail);
            return Optional.of(new Outcome(Set.of(), Set.of(), detail));
        }

        // 守护二：坏得太多就整批不动
        if (unknown.size() > maxUnknownPerBatch) {
            String detail = "一批 " + batch.size() + " 只里有 " + unknown.size() + " 只富途不认识，超过阈值 "
                    + maxUnknownPerBatch + "，整批不动也不标记（更像富途侧出了事）：" + head(unknown);
            log.warn(detail);
            return Optional.of(new Outcome(Set.of(), Set.of(), detail));
        }

        Set<String> marked = new LinkedHashSet<>();
        if (mayMark) {
            // 用批次里的原 Instrument 去查，别拿代码重建——市场是它自带的
            Map<String, Instrument> bySymbol = new LinkedHashMap<>();
            batch.forEach(i -> bySymbol.putIfAbsent(i.symbol().toUpperCase(Locale.ROOT), i));
            for (String symbol : unknown) {
                Instrument instrument = bySymbol.get(symbol);
                if (instrument == null) {
                    continue;
                }
                Optional<InstrumentRow> row = instruments.find(instrument);
                if (row.isPresent() && !"UNRESOLVED".equals(row.get().resolveStatus())) {
                    instruments.markUnresolved(row.get().id());
                    marked.add(symbol);
                }
            }
        }

        String detail = "富途不认识 " + unknown + (named.isEmpty() ? "" : "（它只点名了 " + named + "）")
                + "；已" + (mayMark ? "标成 UNRESOLVED " + marked : "剔除但不写库（持仓侧）")
                + "，剔除后重试本批";
        log.warn(detail);
        return Optional.of(new Outcome(unknown, marked, detail));
    }

    /** 对整批取静态信息，收集富途不认识的代码：brokerId==0 的，以及请求了却没回的。 */
    private Set<String> findUnknown(List<Instrument> batch) throws Exception {
        Set<String> unknown = new LinkedHashSet<>();
        for (int from = 0; from < batch.size(); from += staticBatchSize) {
            List<Instrument> chunk = batch.subList(from, Math.min(from + staticBatchSize, batch.size()));
            List<InstrumentStatic> statics = gateway.staticInfo(chunk).get(30, TimeUnit.SECONDS);
            Set<String> answered = new LinkedHashSet<>();
            for (InstrumentStatic s : statics) {
                String symbol = s.instrument().symbol().toUpperCase(Locale.ROOT);
                answered.add(symbol);
                // 实测：富途对不认识的代码也回一条（名称"未知股票"、brokerId=0、delisted=true）
                if (s.brokerId() == 0) {
                    unknown.add(symbol);
                }
            }
            for (Instrument i : chunk) {
                String symbol = i.symbol().toUpperCase(Locale.ROOT);
                if (!answered.contains(symbol)) {
                    unknown.add(symbol);
                }
            }
        }
        return unknown;
    }

    /** 走到最里层的 GatewayException：调用方都是 {@code future.get()}，异常被 ExecutionException 包着。 */
    private static RequestRejectedException rejection(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof RequestRejectedException r) {
                return r;
            }
        }
        return null;
    }

    private static String head(Set<String> s) {
        List<String> l = List.copyOf(s);
        return l.size() <= 12 ? l.toString() : l.subList(0, 12) + "…";
    }
}
