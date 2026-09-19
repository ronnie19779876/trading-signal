package org.jdkxx.trader.core.account;

import org.jdkxx.trader.domain.AccountPnl;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.PositionPnl;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.GatewayState;
import org.jdkxx.trader.gateway.LiveAccountGateway;
import org.jdkxx.trader.gateway.LiveAccountListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 实时账户（3.0.2 起）：按需订阅盈透的资金、盈亏与持仓，给仪表盘读。只在内存里，不落库、不进快照。
 *
 * <p><b>按需</b>：有人来读才订阅，{@value #IDLE_MINUTES} 分钟没人读就退订——这个网关同时在给真正下单的系统服务，能少占就少占。
 * 刚订上的几秒里数据陆续到齐，状态是 WARMING。
 *
 * <p><b>实时净值</b>是估算：现金 + 应计股息 + 逐只市值之和。盈透的账户汇总约 3 分钟才推一次，而逐只市值秒级更新；
 * 2026-09-19 实测同一时刻逐只市值之和与汇总的股票市值精确相等，恒等式"现金 + 股票市值 + 应计股息 = 净值"在 09-14 也验证过。
 * 只对全是股票的账户估算（有期权等其他品种时恒等式不成立），缺任何一只的市值就不估，退回汇总净值。
 *
 * <p>监听回调在网关的 dispatch 线程上，只写 volatile 字段；读在请求线程上。
 */
public class LiveAccountService implements LiveAccountListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LiveAccountService.class);
    static final long IDLE_MINUTES = 5;

    public enum Status {
        /** 数据在推送 */
        LIVE,
        /** 刚订上，数据还没到齐 */
        WARMING,
        /** 网关没连上：保留最后收到的数据，各自带时间 */
        DISCONNECTED,
        /** 取不到（网关未启用、选不出账户等），detail 说明原因 */
        UNAVAILABLE
    }

    /** 资金（盈透账户汇总，约 3 分钟一推）。 */
    public record Money(BigDecimal netLiquidation, BigDecimal totalCash, BigDecimal availableFunds, BigDecimal buyingPower,
                        BigDecimal excessLiquidity, BigDecimal grossPositionValue, BigDecimal stockMarketValue,
                        BigDecimal accruedDividend, Instant updatedAt) {
    }

    /** 净值：estimate 为实时估算（缺数据时为 null），summary 为盈透汇总的原值。 */
    public record Nav(BigDecimal estimate, Instant estimateAt, BigDecimal summary, Instant summaryAt) {
    }

    /** 盈亏。source：ACCOUNT = 盈透账户盈亏；POSITIONS = 账户盈亏还没到时由逐只盈亏加总（实测两者精确相等）。 */
    public record Pnl(BigDecimal daily, BigDecimal unrealized, BigDecimal realized, String source, Instant updatedAt) {
    }

    public record LivePosition(String symbol, String conId, String securityType, String currency, BigDecimal quantity,
                               BigDecimal averageCost, BigDecimal price, BigDecimal marketValue, BigDecimal dailyPnl,
                               BigDecimal unrealizedPnl, boolean cashEquivalent, Instant updatedAt) {
    }

    public record LiveView(Status status, String detail, String accountMask, String currency, Instant startedAt,
                           Nav nav, Money money, Pnl pnl, List<LivePosition> positions, Instant positionsUpdatedAt,
                           String lastError) {
    }

    private final AccountProperties props;
    private final String configuredAccount;
    private final BrokerGateway broker;
    private final LiveAccountGateway live;
    private final Clock clock;
    private final ScheduledExecutorService idle;

    private volatile String accountId;
    private volatile Instant lastAccess = Instant.EPOCH;
    private volatile Instant startedAt;
    private volatile AccountSummary summary;
    private volatile AccountPnl pnl;
    private volatile List<Position> positions;
    private volatile Instant positionsAt;
    private final Map<String, PositionPnl> singles = new ConcurrentHashMap<>();
    private volatile String lastError;

    public LiveAccountService(AccountProperties props, String configuredAccount, BrokerGateway broker, LiveAccountGateway live,
                              Clock clock) {
        this.props = props;
        this.configuredAccount = configuredAccount;
        this.broker = broker;
        this.live = live;
        this.clock = clock;
        this.idle = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "live-account-idle");
            t.setDaemon(true);
            return t;
        });
        idle.scheduleWithFixedDelay(this::stopIfIdle, 30, 30, TimeUnit.SECONDS);
    }

    /** 读一次实时账户。第一次读（或闲置退订后再读）会发起订阅，返回 WARMING。 */
    public LiveView view() {
        lastAccess = clock.instant();
        if (!broker.enabled()) {
            return unavailable("盈透网关未启用（trader.ibkr.enabled=false）");
        }
        if (!live.liveActive()) {
            String reason = start();
            if (reason != null) {
                return unavailable(reason);
            }
        }
        return snapshotView();
    }

    /** @return 失败原因；成功为 null */
    private String start() {
        if (broker.status().state() != GatewayState.CONNECTED) {
            return "盈透网关未连接：" + broker.status().detail();
        }
        try {
            List<String> ids = broker.accounts().get(10, TimeUnit.SECONDS).stream().map(AccountRef::accountId).toList();
            String chosen = AccountPositions.choose(configuredAccount, ids);
            clear();
            accountId = chosen;
            startedAt = clock.instant();
            live.startLive(chosen, this);
            log.info("实时账户：开始订阅（{}）", AccountKeys.mask(chosen));
            return null;
        } catch (Exception e) {
            Throwable c = e.getCause() != null && e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
            return "选不出盈透账户：" + (c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage());
        }
    }

    void stopIfIdle() {
        try {
            if (live.liveActive() && Duration.between(lastAccess, clock.instant()).toMinutes() >= IDLE_MINUTES) {
                live.stopLive();
                clear();
                log.info("实时账户：{} 分钟没人读，已退订", IDLE_MINUTES);
            }
        } catch (RuntimeException e) {
            log.warn("实时账户闲置检查出错：{}", e.toString());
        }
    }

    private void clear() {
        summary = null;
        pnl = null;
        positions = null;
        positionsAt = null;
        singles.clear();
        lastError = null;
    }

    private LiveView unavailable(String reason) {
        return new LiveView(Status.UNAVAILABLE, reason, null, null, null, null, null, null, List.of(), null, null);
    }

    LiveView snapshotView() {
        AccountSummary s = summary;
        List<Position> ps = positions;
        AccountPnl p = pnl;
        boolean connected = broker.status().state() == GatewayState.CONNECTED;
        Status status = !connected ? Status.DISCONNECTED : (s == null || ps == null) ? Status.WARMING : Status.LIVE;
        String detail = switch (status) {
            case DISCONNECTED -> "盈透网关未连接：" + broker.status().detail() + "；下面是断线前最后收到的数据";
            case WARMING -> "刚订阅，数据陆续到齐（通常 2 秒内）";
            default -> null;
        };
        List<LivePosition> rows = ps == null ? List.of() : positionRows(ps);
        return new LiveView(status, detail, accountId == null ? null : AccountKeys.mask(accountId),
                s == null ? null : s.currency(), startedAt, nav(s, ps, rows), money(s), pnl(p, ps, rows), rows, positionsAt,
                lastError);
    }

    private List<LivePosition> positionRows(List<Position> ps) {
        Set<String> cash = props.cashEquivalents().stream().map(AccountPositions::normalize).collect(Collectors.toSet());
        List<LivePosition> out = new ArrayList<>();
        for (Position q : ps) {
            PositionPnl v = singles.get(q.brokerRef());
            String symbol = AccountPositions.normalize(q.symbol());
            BigDecimal value = v == null ? null : cents(v.marketValue());
            BigDecimal price = value == null || q.quantity().signum() == 0 ? null
                    : value.divide(q.quantity(), 4, RoundingMode.HALF_UP);
            out.add(new LivePosition(symbol, q.brokerRef(), q.securityType(), q.currency(), q.quantity(), q.averageCost(), price,
                    value, v == null ? null : cents(v.daily()), v == null ? null : cents(v.unrealized()), cash.contains(symbol),
                    v == null ? null : v.receivedAt()));
        }
        return out;
    }

    /** 盈透推送的是 double，转成 BigDecimal 会带出浮点尾巴（238007.7728881836）；金额只留到分。 */
    static BigDecimal cents(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    /** 实时净值 = 现金 + 应计股息 + 逐只市值之和。非股票品种或缺任何一只的市值时不估。 */
    static Nav nav(AccountSummary s, List<Position> ps, List<LivePosition> rows) {
        if (s == null) {
            return new Nav(null, null, null, null);
        }
        BigDecimal estimate = null;
        Instant at = null;
        boolean stocksOnly = ps != null && ps.stream().allMatch(p -> "STK".equals(p.securityType()));
        boolean allValued = rows.stream().allMatch(r -> r.marketValue() != null);
        if (ps != null && stocksOnly && allValued && s.totalCash() != null) {
            estimate = s.totalCash().add(Objects.requireNonNullElse(s.accruedDividend(), BigDecimal.ZERO));
            for (LivePosition r : rows) {
                estimate = estimate.add(r.marketValue());
                at = at == null || r.updatedAt().isAfter(at) ? r.updatedAt() : at;
            }
            at = at == null ? s.receivedAt() : at;
        }
        return new Nav(estimate, at, s.netLiquidation(), s.receivedAt());
    }

    private static Money money(AccountSummary s) {
        return s == null ? null : new Money(s.netLiquidation(), s.totalCash(), s.availableFunds(), s.buyingPower(),
                s.excessLiquidity(), s.grossPositionValue(), s.stockMarketValue(), s.accruedDividend(), s.receivedAt());
    }

    /** 账户盈亏优先；还没到（首条被丢、价格静止时第二条迟迟不来）就用逐只盈亏加总。 */
    static Pnl pnl(AccountPnl p, List<Position> ps, List<LivePosition> rows) {
        if (p != null) {
            return new Pnl(cents(p.daily()), cents(p.unrealized()), cents(p.realized()), "ACCOUNT", p.receivedAt());
        }
        if (ps == null || ps.isEmpty() || rows.stream().anyMatch(r -> r.dailyPnl() == null || r.unrealizedPnl() == null)) {
            return null;
        }
        BigDecimal daily = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        Instant at = null;
        for (LivePosition r : rows) {
            daily = daily.add(r.dailyPnl());
            unrealized = unrealized.add(r.unrealizedPnl());
            at = at == null || r.updatedAt().isAfter(at) ? r.updatedAt() : at;
        }
        return new Pnl(daily, unrealized, null, "POSITIONS", at);
    }

    // ------------------------------------------------------------------ 推送（网关 dispatch 线程）

    @Override
    public void onSummary(AccountSummary s) {
        summary = s;
    }

    @Override
    public void onPnl(AccountPnl p) {
        pnl = p;
    }

    @Override
    public void onPositions(List<Position> list) {
        positions = List.copyOf(list);
        positionsAt = clock.instant();
        Set<String> refs = list.stream().map(Position::brokerRef).collect(Collectors.toSet());
        singles.keySet().retainAll(refs);   // 清仓的不再算进净值
    }

    @Override
    public void onPositionPnl(PositionPnl p) {
        singles.put(p.brokerRef(), p);
    }

    @Override
    public void onLiveError(String what, GatewayException error) {
        lastError = what + "：" + error.getMessage();
        log.warn("实时账户 {} 被拒：{}", what, error.getMessage());
    }

    @Override
    public void close() {
        idle.shutdownNow();
        if (live.liveActive()) {
            live.stopLive();
        }
    }
}
