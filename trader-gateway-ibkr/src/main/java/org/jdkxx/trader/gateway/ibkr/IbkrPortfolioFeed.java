package org.jdkxx.trader.gateway.ibkr;

import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 账户更新推送的<b>常驻订阅</b>（reqAccountUpdates → updatePortfolio，3.4.6，ARCHITECTURE §20.8）：
 * 连上就订、断开才退，给持仓的<b>均价与浮盈</b>提供与盈透 App 同源的值。
 *
 * <p>为什么要它（2026-10-20 ~ 10-22 生产实测）：当天加仓 GOOG 100 股（62 → 162）后，
 * {@code reqPositionsMulti} 与 {@code reqPositions} 的均价仍是加仓前的 346.806，{@code reqPnLSingle} 的浮盈也按它算；
 * App 显示 339.525。只有 {@code updatePortfolio} 给 339.5247。网关次日每日重启后 {@code reqPositionsMulti} 改对了，
 * {@code reqPositions} 与 {@code reqPnLSingle} <b>仍然没改</b>——所以不能指望它自己好。
 *
 * <p>约束：
 * <ul>
 *   <li><b>同一账户同一时间只能有一个 API 客户端订阅</b>（盈透文档；第二个会把第一个挤掉，被挤的收 2100 / 2101）。
 *       所以只在生产实例启用（{@code trader.environment=PROD}），开发实例与临时探针都不得订它；</li>
 *   <li>首批约 0.3 秒到齐（{@code accountDownloadEnd}），之后有变化或<b>约 3 分钟</b>推一批（09-19 实测）：
 *       加仓后成本最多晚 3 分钟更新；</li>
 *   <li>浮盈按盈透自己的标价算（与行情 lastPrice 差几分钱），照原值用，不折算。</li>
 * </ul>
 * 只在<b>数量与持仓接口一致</b>时提供成本与浮盈（成交中途两边不同步时宁可先用原来的来源）；
 * 被退订 / 被占用（2100 / 2101）时清空，调用方自动退回原来的来源。<b>所有方法都在 dispatch 线程上调用</b>，
 * 只有 {@link #rows()} 可以跨线程读（不可变快照）。
 */
final class IbkrPortfolioFeed {

    private static final Logger log = LoggerFactory.getLogger(IbkrPortfolioFeed.class);

    /** 一批里逐只推送，攒一下再生效（与账户汇总同一做法）。 */
    static final long DEBOUNCE_MS = 300;

    private final IbkrLiveAccount.Wire wire;
    private final String accountId;

    /** 订上时所在的会话；null = 当前没有订阅 */
    private Object bound;
    private boolean ready;
    private Runnable flush;
    private Runnable onChange = () -> { };
    private final Map<String, IbkrAccounts.PortfolioRow> pending = new LinkedHashMap<>();
    private volatile Map<String, IbkrAccounts.PortfolioRow> published = Map.of();
    /** 均价不一致的 WARN 每组数只记一次 */
    private final Set<String> costDiffLogged = new HashSet<>();
    private boolean noAccountLogged;
    private String lastError;

    IbkrPortfolioFeed(IbkrLiveAccount.Wire wire, String accountId) {
        this.wire = wire;
        this.accountId = accountId;
    }

    /** 每批生效后回调（实时账户据此重发持仓与逐只盈亏）。 */
    void onChange(Runnable r) {
        this.onChange = r == null ? () -> { } : r;
    }

    boolean subscribed() {
        return bound != null && bound == wire.sessionToken();
    }

    String lastError() {
        return lastError;
    }

    /** 最近一批已生效的推送（conId → 行）；没订上、还没到齐、或被退订时为空。可跨线程读。 */
    Map<String, IbkrAccounts.PortfolioRow> rows() {
        return published;
    }

    /**
     * @param afterRecovery 连接刚恢复（断线重连或 1101）：1101 时会话不变而券商已丢弃订阅，必须重订（同账户汇总）。
     */
    boolean subscribe(boolean afterRecovery) {
        if (accountId == null || accountId.isBlank()) {
            if (!noAccountLogged) {
                log.warn("账户更新推送没有配置账户（trader.ibkr.account），不订阅；持仓均价与浮盈沿用原来的来源");
                noAccountLogged = true;
            }
            return false;
        }
        Object current = wire.sessionToken();
        if (current == null) {
            return false;
        }
        if (subscribed() && !afterRecovery) {
            return true;
        }
        if (subscribed()) {
            log.info("连接恢复（会话未变，多半是 1101 数据丢失）：重订账户更新推送");
            cancelOnBroker();
        }
        clear();
        bound = current;
        wire.open(IbkrSubscriptions.PORTFOLIO, handler());
        if (!wire.subscribe("账户更新推送", c -> c.reqAccountUpdates(true, accountId))) {
            wire.close(IbkrSubscriptions.PORTFOLIO);
            bound = null;
            return false;
        }
        return true;
    }

    /** 断开 / 关停时退订。 */
    void stop() {
        if (subscribed()) {
            cancelOnBroker();
        } else {
            wire.close(IbkrSubscriptions.PORTFOLIO);
        }
        bound = null;
        clear();
        publish();
    }

    /**
     * 这只持仓在推送里的均价——<b>只有数量与持仓接口一致时才给</b>，否则 null（用原来的来源）。
     * 两边均价不一致时记一条 WARN（每组数一次），用来积累「持仓接口何时滞后、何时自行改对」的样本。
     */
    static BigDecimal cost(Map<String, IbkrAccounts.PortfolioRow> rows, String conId, BigDecimal quantity,
                           BigDecimal positionsCost, String symbol, Set<String> logged) {
        IbkrAccounts.PortfolioRow r = matching(rows, conId, quantity);
        if (r == null) {
            return null;
        }
        BigDecimal feed = IbkrAccounts.amount(r.averageCost());
        if (feed != null && positionsCost != null && feed.subtract(positionsCost).abs().compareTo(new BigDecimal("0.000001")) > 0) {
            String key = conId + "|" + positionsCost.toPlainString() + "|" + feed.toPlainString();
            if (logged.add(key)) {
                log.warn("持仓接口与账户更新推送的均价不一致（{}，数量 {}）：持仓接口 {}，账户更新推送 {}；采用后者（与盈透 App 同源）",
                        symbol, quantity.stripTrailingZeros().toPlainString(), positionsCost.toPlainString(), feed.toPlainString());
            }
        }
        return feed;
    }

    /** 这只持仓在推送里的浮盈——同样只在数量一致时给。 */
    static BigDecimal unrealized(Map<String, IbkrAccounts.PortfolioRow> rows, String conId, BigDecimal quantity) {
        IbkrAccounts.PortfolioRow r = matching(rows, conId, quantity);
        return r == null ? null : IbkrAccounts.amount(r.unrealized());
    }

    Set<String> costDiffLogged() {
        return costDiffLogged;
    }

    private static IbkrAccounts.PortfolioRow matching(Map<String, IbkrAccounts.PortfolioRow> rows, String conId,
                                                      BigDecimal quantity) {
        IbkrAccounts.PortfolioRow r = rows.get(conId);
        if (r == null || quantity == null || r.position() == null || !r.position().isValid()) {
            return null;
        }
        return r.position().value().compareTo(quantity) == 0 ? r : null;
    }

    private void cancelOnBroker() {
        wire.close(IbkrSubscriptions.PORTFOLIO);    // 先摘处理器：自己退订引起的 2100 不该当成被挤掉
        String acct = accountId;
        wire.unsubscribe("账户更新推送", c -> c.reqAccountUpdates(false, acct));
    }

    private void clear() {
        pending.clear();
        ready = false;
        if (flush != null) {
            flush.run();
            flush = null;
        }
    }

    private IbkrSubscriptions.Handler handler() {
        return new IbkrSubscriptions.Handler() {
            @Override
            public void item(Object item) {
                IbkrAccounts.PortfolioRow r = (IbkrAccounts.PortfolioRow) item;
                if (r.contract() == null || !accountId.equals(r.account())) {
                    return;
                }
                String conId = Integer.toString(r.contract().conid());
                if (r.position() == null || !r.position().isValid() || r.position().value().signum() == 0) {
                    pending.remove(conId);    // 清仓（或无效数量）：不再提供
                } else {
                    pending.put(conId, r);
                }
                if (ready && flush == null) {
                    flush = wire.later(Duration.ofMillis(DEBOUNCE_MS), IbkrPortfolioFeed.this::flushNow);
                }
            }

            @Override
            public void end() {
                ready = true;
                lastError = null;
                if (flush != null) {
                    flush.run();
                    flush = null;
                }
                flushNow();
            }

            @Override
            public void error(RequestRejectedException e) {
                lastError = e.getMessage();
                log.warn("账户更新推送失效（{}）：持仓均价与浮盈退回持仓接口 / 逐只盈亏的原值，下次连上再订。"
                        + "若不是本实例自己退订，多半是有别的 API 客户端订了同一账户", e.getMessage());
                bound = null;
                wire.close(IbkrSubscriptions.PORTFOLIO);
                clear();
                publish();
            }
        };
    }

    private void flushNow() {
        flush = null;
        publish();
    }

    private void publish() {
        published = ready ? Map.copyOf(pending) : Map.of();
        try {
            onChange.run();
        } catch (RuntimeException e) {
            log.warn("账户更新推送生效后回调出错：{}", e.toString());
        }
    }
}
