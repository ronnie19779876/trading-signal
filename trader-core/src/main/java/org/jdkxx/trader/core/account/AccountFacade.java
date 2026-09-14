package org.jdkxx.trader.core.account;

import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.SnapshotWindow;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.storage.account.AccountSnapshotRepository;
import org.jdkxx.trader.storage.account.AccountSnapshotRow;
import org.jdkxx.trader.storage.account.PositionSnapshotRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 账户与持仓的入口：提交快照作业、查快照（附与上一份快照相比的日变化）。
 */
public class AccountFacade {

    /**
     * 与同一账户上一份快照相比的变化，查询时现算、不落库。
     *
     * @param netLiquidationChange 净值变化，<b>含出入金</b>（第 3 期不区分）
     * @param positionPnl          Σ 两份快照都持有的 上一份数量 × (本次价格 − 上次价格)；缺价的不计
     * @param positionsChanged     持仓集合或数量变过：当天有买卖，positionPnl 只是近似
     */
    public record DailyChange(LocalDate previousDate, BigDecimal netLiquidationChange, BigDecimal positionPnl,
                              boolean positionsChanged) {
    }

    public record SnapshotView(AccountSnapshotRow snapshot, List<PositionSnapshotRow> positions, DailyChange change) {
    }

    private final JobService jobs;
    private final AccountSnapshotService service;
    private final AccountSnapshotRepository snapshots;
    private final TradingDayRepository days;
    private final String environment;
    private final Clock clock;
    private final ZoneId zone;

    public AccountFacade(JobService jobs, AccountSnapshotService service, AccountSnapshotRepository snapshots,
                         TradingDayRepository days, String environment, Clock clock, ZoneId zone) {
        this.jobs = jobs;
        this.service = service;
        this.snapshots = snapshots;
        this.days = days;
        this.environment = environment;
        this.clock = clock;
        this.zone = zone;
    }

    /**
     * 提交账户快照作业。窗口外直接拒绝，而不是提交一个注定失败的作业。
     * force=true 忽略窗口、按最近一个已收盘交易日口径拍，只允许开发环境——
     * 在生产上它会拿非收盘时刻的持仓冒充某个交易日的收盘快照。
     */
    public long snapshot(String trigger, boolean force) {
        if (force) {
            if (!"DEV".equals(environment)) {
                throw new IllegalStateException("force=true 只允许在开发环境（trader.environment=DEV）使用");
            }
        } else if (SnapshotWindow.asOfDate(ZonedDateTime.now(clock.withZone(zone)), d -> days.isTradingDay(Market.US, d)).isEmpty()) {
            throw new IllegalStateException(SnapshotWindow.OUTSIDE);
        }
        return jobs.submit(Jobs.ACCOUNT_SNAPSHOT, trigger, ctx -> service.run(ctx, force));
    }

    public Optional<SnapshotView> latest() {
        return snapshots.latest().map(s -> {
            List<PositionSnapshotRow> positions = snapshots.positions(s.id());
            DailyChange change = snapshots.previous(s.accountKey(), s.asOfDate())
                    .map(p -> change(p, snapshots.positions(p.id()), s, positions))
                    .orElse(null);
            return new SnapshotView(s, positions, change);
        });
    }

    public List<AccountSnapshotRow> between(LocalDate from, LocalDate to) {
        return snapshots.between(from, to);
    }

    static DailyChange change(AccountSnapshotRow prev, List<PositionSnapshotRow> prevPositions,
                              AccountSnapshotRow cur, List<PositionSnapshotRow> curPositions) {
        BigDecimal nav = prev.netLiquidation() == null || cur.netLiquidation() == null ? null
                : cur.netLiquidation().subtract(prev.netLiquidation());
        Map<String, PositionSnapshotRow> before = new HashMap<>();
        prevPositions.forEach(p -> before.put(p.brokerRef(), p));
        BigDecimal pnl = BigDecimal.ZERO;
        boolean changed = prevPositions.size() != curPositions.size();
        for (PositionSnapshotRow c : curPositions) {
            PositionSnapshotRow p = before.get(c.brokerRef());
            if (p == null) {
                changed = true;
                continue;
            }
            if (p.quantity().compareTo(c.quantity()) != 0) {
                changed = true;
            }
            if (p.price() != null && c.price() != null) {
                pnl = pnl.add(p.quantity().multiply(c.price().subtract(p.price())));
            }
        }
        return new DailyChange(prev.asOfDate(), nav, pnl.setScale(4, RoundingMode.HALF_UP), changed);
    }
}
