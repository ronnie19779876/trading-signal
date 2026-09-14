package org.jdkxx.trader.core.account;

import org.jdkxx.trader.core.account.ValuedPosition.PriceSource;
import org.jdkxx.trader.core.marketdata.bars.DailyIncrementService;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.ValuationSnapshot;
import org.jdkxx.trader.gateway.AccountGateway;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.account.AccountSnapshotRepository;
import org.jdkxx.trader.storage.account.AccountSnapshotRow;
import org.jdkxx.trader.storage.account.PositionSnapshotRow;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.PoolRepository;
import org.jdkxx.trader.storage.marketdata.PoolRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 账户快照作业体：盈透持仓与资金汇总 → 映射到本系统标的 → 按收盘价估值 → 按持仓同步池里的 HOLDING → 对账 → 落库（同一天重拍覆盖）。
 *
 * <p>价格按优先级取：当日 K 线收盘（BAR）→ 富途快照价（SNAPSHOT，给库里没有当日 K 线的持仓，
 * 比如只进快照不进池的现金管理工具）→ 缺价（NONE，对账记 WARN）。
 *
 * <p>快照价什么时候算"当日收盘"：富途快照的当前价收盘后冻结在常规时段收盘，盘后、夜盘都不动，直到下一个交易日；
 * 快照时间戳却跟着盘后/夜盘更新。实测美东周日 20:52 取 SPY：时间戳是周日，价格 764.29 与周五 K 线收盘完全一致。
 * 所以<b>不能用时间戳判断价格属于哪天</b>，只看此刻是否还没到下一个交易日的 04:00（美东）。
 */
public class AccountSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(AccountSnapshotService.class);

    private final AccountProperties props;
    private final AccountPositions source;
    private final MarketDataGateway market;
    private final InstrumentRepository instruments;
    private final DailyBarRepository bars;
    private final PoolRepository pool;
    private final TradingDayRepository days;
    private final AccountSnapshotRepository snapshots;
    private final HoldingSyncService holdingSync;
    private final Clock clock;
    private final ZoneId zone;

    /**
     * @param holdingSync 为 null 时不同步池里的 HOLDING（只拍快照、对账）
     */
    public AccountSnapshotService(AccountProperties props, String configuredAccount, BrokerGateway broker, AccountGateway accounts,
                                  MarketDataGateway market, InstrumentRepository instruments, DailyBarRepository bars,
                                  PoolRepository pool, TradingDayRepository days, AccountSnapshotRepository snapshots,
                                  HoldingSyncService holdingSync, Clock clock, ZoneId zone) {
        this.props = props;
        this.source = new AccountPositions(configuredAccount, broker, accounts, instruments);
        this.market = market;
        this.instruments = instruments;
        this.bars = bars;
        this.pool = pool;
        this.days = days;
        this.snapshots = snapshots;
        this.holdingSync = holdingSync;
        this.clock = clock;
        this.zone = zone;
    }

    /**
     * @param force 忽略快照窗口、按最近一个已收盘交易日口径拍（只给开发环境验证用，由上层把关）
     */
    public String run(JobContext ctx, boolean force) throws Exception {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        LocalDate asOf = force ? latestSettledTradingDay(now)
                : SnapshotWindow.asOfDate(now, d -> days.isTradingDay(Market.US, d))
                        .orElseThrow(() -> new IllegalStateException(SnapshotWindow.OUTSIDE));
        AccountKeys.requireSecret(props.keySecret());
        String accountId = source.chooseAccount();
        String mask = AccountKeys.mask(accountId);

        ctx.progress("取盈透持仓与资金汇总（" + mask + "）");
        List<Position> all = source.positions(accountId);
        AccountSummary summary = source.summary(accountId);
        List<Position> held = all.stream().filter(p -> p.quantity().signum() != 0).toList();

        ctx.progress("按 " + asOf + " 收盘估值 " + held.size() + " 条持仓");
        List<ValuedPosition> valued = value(held, asOf, now);

        String syncNote = "";
        if (holdingSync != null) {
            ctx.progress("按持仓同步池里的 HOLDING");
            try {
                HoldingSyncService.Result s = holdingSync.syncFromSnapshot(valued);
                syncNote = "；持仓同步：" + s.summary();
                if (!s.errors().isEmpty() || s.plan().blocked() != null) {
                    ctx.partial("持仓同步未完全执行");
                }
            } catch (RuntimeException e) {
                log.warn("持仓同步失败：{}", e.toString());
                syncNote = "；持仓同步失败：" + e.getMessage();
                ctx.partial("持仓同步失败");
            }
        }

        List<PoolRow> members = pool.findAll();
        Map<Long, String> holdings = new LinkedHashMap<>();
        instruments.findByIds(members.stream().filter(m -> m.role() == PoolRole.HOLDING).map(PoolRow::instrumentId).toList())
                .forEach(r -> holdings.put(r.id(), r.symbol()));
        Set<Long> benchmarks = members.stream().filter(m -> m.role() == PoolRole.BENCHMARK).map(PoolRow::instrumentId)
                .collect(Collectors.toSet());
        AccountReconciler.Result r = AccountReconciler.reconcile(summary, valued, holdings, benchmarks,
                props.valueTolerance(), props.identityTolerance());

        AccountSnapshotRow header = new AccountSnapshotRow(0, Broker.IBKR.name(), AccountKeys.key(props.keySecret(), Broker.IBKR, accountId),
                mask, asOf, clock.instant(), summary.currency(), summary.netLiquidation(), summary.totalCash(),
                summary.stockMarketValue(), summary.grossPositionValue(), summary.availableFunds(), summary.buyingPower(),
                summary.excessLiquidity(), summary.unrealizedPnl(), summary.realizedPnl(), summary.accruedDividend(),
                r.positionValue(), valued.size(), r.status().name(), null, ctx.id());
        List<PositionSnapshotRow> rows = valued.stream().map(v -> new PositionSnapshotRow(v.position().brokerRef(), v.symbol(),
                v.instrumentId(), v.position().securityType(), v.position().currency(), v.position().exchange(),
                v.position().quantity(), v.position().averageCost(), v.price(), v.priceSource().name(), v.marketValue(),
                v.costBasis(), v.unrealizedPnl(), v.cashEquivalent())).toList();
        snapshots.save(header, summary.raw(),
                r.checks().stream().map(c -> new AccountSnapshotRepository.ReconCheck(c.name(), c.status().name(), c.detail())).toList(),
                rows);

        if (r.status() != AccountReconciler.Status.OK) {
            ctx.partial("对账 " + r.status());
        }
        return "账户快照 " + asOf + "（" + mask + "）：持仓 " + held.size() + " 条，价格 " + sources(valued)
                + "，对账 " + r.status() + "；" + r.brief() + syncNote;
    }

    private LocalDate latestSettledTradingDay(ZonedDateTime now) {
        LocalDate d = DailyIncrementService.expectedLatestTradingDay(
                days.between(Market.US, now.toLocalDate().minusDays(45), now.toLocalDate()), now);
        if (d == null) {
            throw new IllegalStateException("交易日历为空，无法确定快照所属交易日");
        }
        return d;
    }

    List<ValuedPosition> value(List<Position> held, LocalDate asOf, ZonedDateTime now) {
        Set<String> cash = props.cashEquivalents().stream().map(AccountPositions::normalize).collect(Collectors.toSet());
        boolean snapshotIsClose = snapshotPriceIsClose(now, asOf);
        List<Long> ids = new ArrayList<>(held.size());
        for (Position p : held) {
            ids.add(source.instrumentId(p));
        }
        Map<Long, BigDecimal> closes = bars.closesOn(asOf, ids.stream().filter(Objects::nonNull).distinct().toList());

        List<String> needSnapshot = new ArrayList<>();
        for (int i = 0; i < held.size(); i++) {
            Long id = ids.get(i);
            if (AccountPositions.stockUsd(held.get(i)) && (id == null || !closes.containsKey(id))) {
                needSnapshot.add(AccountPositions.normalize(held.get(i).symbol()));
            }
        }
        Map<String, ValuationSnapshot> snaps = snapshotPrices(needSnapshot);

        List<ValuedPosition> out = new ArrayList<>(held.size());
        for (int i = 0; i < held.size(); i++) {
            Position p = held.get(i);
            Long id = ids.get(i);
            String symbol = AccountPositions.normalize(p.symbol());
            boolean isCash = cash.contains(symbol);
            if (id != null && closes.containsKey(id)) {
                out.add(new ValuedPosition(p, id, closes.get(id), PriceSource.BAR, isCash));
                continue;
            }
            ValuationSnapshot s = AccountPositions.stockUsd(p) ? snaps.get(symbol) : null;
            if (s != null && s.lastPrice() != null && s.lastPrice().signum() > 0 && snapshotIsClose) {
                out.add(new ValuedPosition(p, id, s.lastPrice(), PriceSource.SNAPSHOT, isCash));
            } else {
                log.info("{} 没有 {} 的价格（无当日 K 线，快照价缺失或已不是当日收盘）", symbol, asOf);
                out.add(new ValuedPosition(p, id, null, PriceSource.NONE, isCash));
            }
        }
        return out;
    }

    /** 此刻的快照价是否仍是 asOf 当日收盘：下一个交易日 04:00（美东）之前是。日历里找不到下一个交易日时按次日算，偏保守。 */
    boolean snapshotPriceIsClose(ZonedDateTime now, LocalDate asOf) {
        LocalDate next = days.between(Market.US, asOf.plusDays(1), asOf.plusDays(14)).stream()
                .filter(d -> d.isAfter(asOf)).findFirst().orElse(asOf.plusDays(1));
        return now.isBefore(next.atTime(SnapshotWindow.CLOSES).atZone(zone));
    }

    private Map<String, ValuationSnapshot> snapshotPrices(List<String> symbols) {
        if (symbols.isEmpty()) {
            return Map.of();
        }
        try {
            List<ValuationSnapshot> got = AccountPositions.await(market.snapshots(symbols.stream().distinct().map(Instrument::us).toList()));
            Map<String, ValuationSnapshot> m = new LinkedHashMap<>();
            got.forEach(v -> m.putIfAbsent(v.instrument().symbol(), v));
            return m;
        } catch (Exception e) {
            log.warn("富途快照取价失败，{} 条持仓记为缺价：{}", symbols.size(), e.toString());
            return Map.of();
        }
    }

    private static String sources(List<ValuedPosition> valued) {
        Map<PriceSource, Long> n = new EnumMap<>(PriceSource.class);
        valued.forEach(v -> n.merge(v.priceSource(), 1L, Long::sum));
        return "K线 " + n.getOrDefault(PriceSource.BAR, 0L) + " / 快照 " + n.getOrDefault(PriceSource.SNAPSHOT, 0L)
                + " / 缺价 " + n.getOrDefault(PriceSource.NONE, 0L);
    }
}
