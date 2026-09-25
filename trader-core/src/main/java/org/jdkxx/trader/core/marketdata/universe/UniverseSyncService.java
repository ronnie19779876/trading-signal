package org.jdkxx.trader.core.marketdata.universe;

import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.domain.IndexCode;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.ConstituentRow;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * 成分股同步：来源 → instrument 幂等入库 → 与现任成分做集合差（新增 since / 退出 until）→ 富途静态信息解析。
 * 任一指数的来源失败时保留该指数旧成分并记录，不中断另一指数。
 */
public class UniverseSyncService {

    private static final Logger log = LoggerFactory.getLogger(UniverseSyncService.class);

    public record IndexResult(IndexCode index, int fetched, int added, int removed, int unchanged, String error) {
    }

    private final MarketDataProperties props;
    private final UniverseSource source;
    private final SpyHoldingsCrossCheck spyCheck;
    private final InstrumentRepository instruments;
    private final IndexConstituentRepository constituents;
    private final MarketDataGateway gateway;
    private final ZoneId zone;

    public UniverseSyncService(MarketDataProperties props, UniverseSource source, SpyHoldingsCrossCheck spyCheck,
                               InstrumentRepository instruments, IndexConstituentRepository constituents,
                               MarketDataGateway gateway) {
        this.props = props;
        this.source = source;
        this.spyCheck = spyCheck;
        this.instruments = instruments;
        this.constituents = constituents;
        this.gateway = gateway;
        this.zone = ZoneId.of(props.zone());
    }

    /** 完整同步（作业体）。{@code force=true} 越过退出数守护，见 {@link #apply}。 */
    public String sync(JobContext ctx) {
        return sync(ctx, false);
    }

    public String sync(JobContext ctx, boolean force) {
        List<String> parts = new ArrayList<>();
        Map<IndexCode, List<ConstituentEntry>> fetched = new LinkedHashMap<>();
        for (IndexCode index : IndexCode.values()) {
            ctx.progress("下载 " + index.displayName() + " 成分股");
            try {
                fetched.put(index, source.fetch(index));
            } catch (RuntimeException e) {
                log.warn("{} 成分股下载失败：{}", index, e.toString());
                ctx.partial(index.displayName() + " 来源失败：" + e.getMessage());
            }
        }
        for (Map.Entry<IndexCode, List<ConstituentEntry>> e : fetched.entrySet()) {
            IndexResult r = apply(e.getKey(), e.getValue(), source.name(), force);
            if (r.error() != null) {
                parts.add(e.getKey() + " " + r.error());
                ctx.partial(e.getKey() + " " + r.error());      // 作业记 PARTIAL，进 jobs 健康指标
            } else {
                parts.add(e.getKey() + " 抓到 " + r.fetched() + "：新增 " + r.added() + "、退出 " + r.removed() + "、不变 " + r.unchanged());
            }
        }
        if (props.universe().crossCheckSpy() && fetched.containsKey(IndexCode.SP500)) {
            ctx.progress("用 SPY 持仓交叉核对标普 500");
            parts.add(crossCheck(fetched.get(IndexCode.SP500)));
        }
        ctx.progress("向富途解析静态信息");
        parts.add(resolveStatics(ctx));
        return String.join("；", parts);
    }

    /** CSV 导入（同一套集合差逻辑，同样受退出数守护）。 */
    public List<IndexResult> importCsv(String csv) {
        return importCsv(csv, false);
    }

    public List<IndexResult> importCsv(String csv, boolean force) {
        List<ConstituentEntry> entries = CsvUniverse.parse(csv);
        Map<IndexCode, List<ConstituentEntry>> byIndex = new LinkedHashMap<>();
        for (ConstituentEntry e : entries) {
            byIndex.computeIfAbsent(e.index(), k -> new ArrayList<>()).add(e);
        }
        List<IndexResult> out = new ArrayList<>();
        for (Map.Entry<IndexCode, List<ConstituentEntry>> e : byIndex.entrySet()) {
            out.add(apply(e.getKey(), e.getValue(), "CSV", force));
        }
        return out;
    }

    /**
     * 按集合差同步一个指数。<b>先算出退出集合并过守护，再动手</b>——被挡下时整个指数一条都不改，
     * 不做部分应用（半新半旧的状态比不同步更难查）。
     *
     * <p>为什么需要守护：{@code apply} 是纯集合差，来源少给多少就退出多少。
     * {@code WikipediaUniverseSource} 只在解析结果少于 50 行时才报错，而标普 500 有 503 只——
     * 页面结构变化让它只解析出 60 行仍然「可信」，一次同步就会把 440 多只标记为退出。
     * 更糟的是**看不见**：{@code BarAuditService} 的分母与这里同源，成分股掉了分母跟着掉，
     * 完整性检查照样全绿，当晚增量直接不再采集这些标的（2026-09-25 全项目审查发现）。
     */
    IndexResult apply(IndexCode index, List<ConstituentEntry> entries, String sourceName, boolean force) {
        LocalDate today = LocalDate.now(zone);
        Map<Long, ConstituentEntry> wanted = new LinkedHashMap<>();
        for (ConstituentEntry e : entries) {
            long id = instruments.upsert(Instrument.us(e.symbol()), e.name());
            wanted.putIfAbsent(id, e);
        }
        Map<Long, ConstituentRow> current = new HashMap<>();
        for (ConstituentRow r : constituents.current(index)) {
            current.put(r.instrumentId(), r);
        }
        List<Long> leaving = current.keySet().stream().filter(id -> !wanted.containsKey(id)).toList();
        int limit = removalLimit(current.size());
        if (!force && leaving.size() > limit) {
            String why = "退出 " + leaving.size() + " 只超过阈值 " + limit + "（现有 " + current.size()
                    + "，来源 " + sourceName + " 只给了 " + wanted.size() + "）：整个指数跳过，未做任何改动。"
                    + "确认来源无误后用 force=true 放行";
            log.warn("{} 成分股同步被守护挡下：{}", index, why);
            return new IndexResult(index, wanted.size(), 0, 0, current.size(), why);
        }
        int added = 0;
        int unchanged = 0;
        for (Map.Entry<Long, ConstituentEntry> w : wanted.entrySet()) {
            ConstituentEntry e = w.getValue();
            if (current.containsKey(w.getKey())) {
                constituents.updateSector(index, w.getKey(), e.sector(), e.subIndustry());
                unchanged++;
            } else {
                constituents.add(index, w.getKey(), e.sector(), e.subIndustry(), today, sourceName);
                added++;
            }
        }
        for (Long id : leaving) {
            constituents.close(index, id, today);
        }
        int removed = leaving.size();
        log.info("{} 成分股同步（{}）：抓到 {}，新增 {}，退出 {}，不变 {}", index, sourceName, wanted.size(), added, removed, unchanged);
        return new IndexResult(index, wanted.size(), added, removed, unchanged, null);
    }

    /** 阈值 = max(绝对值, 现有成员 × 百分比)。现有成员为 0（首次导入）时不设限。 */
    int removalLimit(int currentSize) {
        if (currentSize == 0) {
            return Integer.MAX_VALUE;
        }
        var u = props.universe();
        return Math.max(u.maxRemovalsPerSync(), currentSize * u.maxRemovalsPercent() / 100);
    }

    private String crossCheck(List<ConstituentEntry> sp500) {
        try {
            Set<String> spy = spyCheck.tickers();
            Set<String> wiki = new LinkedHashSet<>();
            sp500.forEach(e -> wiki.add(e.symbol()));
            Set<String> onlyWiki = new TreeSet<>(wiki);
            onlyWiki.removeAll(spy);
            Set<String> onlySpy = new TreeSet<>(spy);
            onlySpy.removeAll(wiki);
            return "SPY 交叉核对：持仓 " + spy.size() + " 只，仅在 Wikipedia " + onlyWiki.size() + " " + head(onlyWiki)
                    + "，仅在 SPY " + onlySpy.size() + " " + head(onlySpy);
        } catch (RuntimeException e) {
            log.warn("SPY 交叉核对失败：{}", e.toString());
            return "SPY 交叉核对失败：" + e.getMessage();
        }
    }

    private static String head(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        return l.size() <= 12 ? l.toString() : l.subList(0, 12) + "…";
    }

    /** 对 PENDING / UNRESOLVED 的标的分批向富途取静态信息；富途不认识的标成 UNRESOLVED。 */
    public String resolveStatics(JobContext ctx) {
        List<InstrumentRow> pending = instruments.findAll().stream()
                .filter(r -> !"RESOLVED".equals(r.resolveStatus())).toList();
        if (pending.isEmpty()) {
            return "静态信息无需解析";
        }
        int resolved = 0;
        int unresolved = 0;
        int batch = Math.max(1, props.universe().staticBatchSize());
        for (int i = 0; i < pending.size(); i += batch) {
            List<InstrumentRow> chunk = pending.subList(i, Math.min(pending.size(), i + batch));
            ctx.progress("解析静态信息 " + (i + chunk.size()) + "/" + pending.size());
            Map<String, InstrumentRow> bySymbol = new HashMap<>();
            chunk.forEach(r -> bySymbol.put(r.symbol(), r));
            List<InstrumentStatic> statics;
            try {
                statics = gateway.staticInfo(chunk.stream().map(InstrumentRow::instrument).toList()).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                ctx.partial("静态信息解析失败：" + e.getMessage());
                return "静态信息解析失败：" + e.getMessage() + "（已解析 " + resolved + "）";
            }
            for (InstrumentStatic s : statics) {
                InstrumentRow row = bySymbol.remove(s.instrument().symbol());
                if (row == null) {
                    continue;
                }
                // 实测：富途对不认识的代码也会回一条（名称"未知股票"、brokerId=0、delisted=true），不能当作已解析
                if (s.brokerId() == 0) {
                    instruments.markUnresolved(row.id());
                    unresolved++;
                } else {
                    instruments.updateStatic(row.id(), s);
                    resolved++;
                }
            }
            for (InstrumentRow miss : bySymbol.values()) {
                instruments.markUnresolved(miss.id());
                unresolved++;
            }
        }
        return "静态信息：解析 " + resolved + "，富途不认识 " + unresolved;
    }
}
