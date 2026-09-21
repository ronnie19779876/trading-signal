package org.jdkxx.trader.core.account;

import org.jdkxx.trader.domain.AccountPnl;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.PositionPnl;
import org.jdkxx.trader.domain.PositionPrice;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 实时账户（3.0.2 起）：按需订阅盈透的资金、盈亏、持仓与持仓标的行情，给仪表盘读。只在内存里，不落库、不进快照。
 *
 * <p><b>与盈透 App 一致</b>（2026-09-19 用户要求，并对照 App 截图逐项核过）：净值、现金等取账户汇总（约 3 分钟一推），
 * 当日 / 浮动 / 已实现盈亏取账户盈亏，逐只市值与盈亏取逐只盈亏，现价与前收取行情。不用本系统的估算替代盈透的数。
 * App 界面上另有三项是它自己用盈透原值算的，这里按同一口径给出：涨跌（最新价 − 前收）与涨跌 %、
 * 成本（Cost Basis = 成本价 × 数量）、占组合（% of Portfolio = 市值 ÷ 净值）——对照截图 GOOG 分别为
 * +2.40 / +0.70%、21,502、4.20%，逐一相等。金额与百分比保留两位（盈透推的是 double，带浮点尾巴）。
 *
 * <p><b>按需</b>：有人来读才订阅，{@value #IDLE_MINUTES} 分钟没人读就退订——这个网关同时在给真正下单的系统服务，能少占就少占。
 * 刚订上的几秒里数据陆续到齐，状态是 WARMING。
 *
 * <p>监听回调在网关的 dispatch 线程上，只写 volatile 字段 / 并发容器；读在请求线程上。
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

    /** 资金（盈透账户汇总原值，约 3 分钟一推）。 */
    public record Money(BigDecimal netLiquidation, BigDecimal totalCash, BigDecimal availableFunds, BigDecimal buyingPower,
                        BigDecimal excessLiquidity, BigDecimal grossPositionValue, BigDecimal stockMarketValue,
                        BigDecimal accruedDividend, Instant updatedAt) {
    }

    /** 盈亏（盈透账户盈亏原值）。 */
    public record Pnl(BigDecimal daily, BigDecimal unrealized, BigDecimal realized, Instant updatedAt) {
    }

    /**
     * 持仓一行。盈透原值：数量与成本价来自持仓，市值 / 当日 / 浮盈来自逐只盈亏（updatedAt），
     * 最新价与前收来自行情（lastAt；lastDelayed 表示降级成了延迟行情）。
     * 与 App 同口径的派生项：change / changePct（最新价 − 前收）、costBasis（成本价 × 数量）、portfolioPct（市值 ÷ 净值 × 100），
     * 缺任一输入时为 null。
     */
    public record LivePosition(String symbol, String conId, String securityType, String currency, BigDecimal quantity,
                               BigDecimal averageCost, BigDecimal costBasis, BigDecimal last, BigDecimal priorClose,
                               BigDecimal change, BigDecimal changePct, Instant lastAt, boolean lastDelayed,
                               BigDecimal marketValue, BigDecimal dailyPnl, BigDecimal unrealizedPnl, BigDecimal portfolioPct,
                               boolean cashEquivalent, Instant updatedAt) {
    }

    public record LiveView(Status status, String detail, String accountMask, String currency, Instant startedAt,
                           Money money, Pnl pnl, List<LivePosition> positions, Instant positionsUpdatedAt,
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
    private final Map<String, PositionPrice> prices = new ConcurrentHashMap<>();
    private volatile String lastError;
    /** lastError 属于哪一路（"实时账户汇总" 等）：那一路再有推送就说明恢复了，把错误清掉 */
    private volatile String lastErrorWhat;

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
        prices.clear();
        lastError = null;
        lastErrorWhat = null;
    }

    private LiveView unavailable(String reason) {
        return new LiveView(Status.UNAVAILABLE, reason, null, null, null, null, null, List.of(), null, null);
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
        List<LivePosition> rows = ps == null ? List.of() : positionRows(ps, s == null ? null : s.netLiquidation());
        return new LiveView(status, detail, accountId == null ? null : AccountKeys.mask(accountId),
                s == null ? null : s.currency(), startedAt, money(s), pnl(p), rows, positionsAt, lastError);
    }

    private List<LivePosition> positionRows(List<Position> ps, BigDecimal netLiquidation) {
        Set<String> cash = props.cashEquivalents().stream().map(AccountPositions::normalize).collect(Collectors.toSet());
        List<LivePosition> out = new ArrayList<>();
        for (Position q : ps) {
            PositionPnl v = singles.get(q.brokerRef());
            PositionPrice px = prices.get(q.brokerRef());
            String symbol = AccountPositions.normalize(q.symbol());
            BigDecimal last = px == null ? null : px.last();
            BigDecimal prior = px == null ? null : px.priorClose();
            BigDecimal value = v == null ? null : cents(v.marketValue());
            BigDecimal change = last == null || prior == null ? null : last.subtract(prior);
            out.add(new LivePosition(symbol, q.brokerRef(), q.securityType(), q.currency(), q.quantity(), q.averageCost(),
                    q.averageCost() == null ? null : cents(q.averageCost().multiply(q.quantity())),
                    last, prior, cents(change), change == null ? null : percent(change, prior),
                    px == null ? null : px.receivedAt(), px != null && px.delayed(),
                    value, v == null ? null : cents(v.daily()), v == null ? null : cents(v.unrealized()),
                    value == null || netLiquidation == null ? null : percent(value, netLiquidation),
                    cash.contains(symbol), v == null ? null : v.receivedAt()));
        }
        return out;
    }

    /** part ÷ whole × 100，两位小数；分母为 0 时为 null。 */
    static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        return whole.signum() == 0 ? null : part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_UP);
    }

    /** 盈透推送的是 double，转成 BigDecimal 会带出浮点尾巴（238007.7728881836）；金额只留到分。格式化，不是计算。 */
    static BigDecimal cents(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static Money money(AccountSummary s) {
        return s == null ? null : new Money(s.netLiquidation(), s.totalCash(), s.availableFunds(), s.buyingPower(),
                s.excessLiquidity(), s.grossPositionValue(), s.stockMarketValue(), s.accruedDividend(), s.receivedAt());
    }

    /** 只用盈透账户盈亏的原值；还没收到有效推送（首条被丢、网关在重订）时为 null，页面显示"等待盈透推送"。 */
    static Pnl pnl(AccountPnl p) {
        return p == null ? null : new Pnl(cents(p.daily()), cents(p.unrealized()), cents(p.realized()), p.receivedAt());
    }

    // ------------------------------------------------------------------ 推送（网关 dispatch 线程）

    @Override
    public void onSummary(AccountSummary s) {
        summary = s;
        recovered("实时账户汇总");
    }

    @Override
    public void onPnl(AccountPnl p) {
        pnl = p;
        recovered("实时账户盈亏");
    }

    @Override
    public void onPositions(List<Position> list) {
        recovered("实时持仓");
        positions = List.copyOf(list);
        positionsAt = clock.instant();
        Set<String> refs = list.stream().map(Position::brokerRef).collect(Collectors.toSet());
        singles.keySet().retainAll(refs);   // 清仓的不再显示
        prices.keySet().retainAll(refs);
    }

    @Override
    public void onPositionPnl(PositionPnl p) {
        singles.put(p.brokerRef(), p);
        recovered("逐只盈亏");
    }

    @Override
    public void onPositionPrice(PositionPrice p) {
        prices.put(p.brokerRef(), p);
        recovered("持仓行情");
    }

    @Override
    public void onLiveError(String what, GatewayException error) {
        lastError = what + "：" + error.getMessage();
        lastErrorWhat = what;
        log.warn("实时账户 {} 被拒：{}", what, error.getMessage());
    }

    /**
     * 出过错的那一路又有推送了：清掉错误，别让界面一直挂着旧的。
     * 3.0.7 及以前不清，2026-09-21 断开重连恢复后页面仍显示那条 322。
     */
    private void recovered(String what) {
        if (what.equals(lastErrorWhat)) {
            lastErrorWhat = null;
            lastError = null;
        }
    }

    @Override
    public void close() {
        idle.shutdownNow();
        if (live.liveActive()) {
            live.stopLive();
        }
    }
}
