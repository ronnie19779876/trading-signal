package org.jdkxx.trader.core.account;

import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.storage.account.AccountSnapshotRepository;
import org.jdkxx.trader.storage.account.AccountSnapshotRow;
import org.jdkxx.trader.storage.account.PositionSnapshotRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 账户与持仓的入口：提交快照作业、查快照。
 */
public class AccountFacade {

    public record SnapshotView(AccountSnapshotRow snapshot, List<PositionSnapshotRow> positions) {
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
        return snapshots.latest().map(s -> new SnapshotView(s, snapshots.positions(s.id())));
    }

    public List<AccountSnapshotRow> between(LocalDate from, LocalDate to) {
        return snapshots.between(from, to);
    }
}
