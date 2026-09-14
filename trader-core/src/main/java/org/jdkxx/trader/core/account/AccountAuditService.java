package org.jdkxx.trader.core.account;

import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService.Check;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService.Report;
import org.jdkxx.trader.core.marketdata.bars.DailyIncrementService;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.storage.account.AccountSnapshotRepository;
import org.jdkxx.trader.storage.account.AccountSnapshotRow;
import org.jdkxx.trader.storage.account.PositionSnapshotRow;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.JobRunRow;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 账户审计，与日线、基本面审计并列进收盘巡检（{@code check-daily.sh} 第三段）。
 *
 * <p>快照定在美东 18:00，巡检常在前后脚跑：当天还没过 18:30 时缺快照只提示、不判失败，过了才是关键项失败——
 * 盈透只给当前状态，这一天的快照过了次日盘前就补不回来。对账 FAIL 是关键项，WARN 与缺价只提示。
 */
public class AccountAuditService {

    static final LocalTime SNAPSHOT_DUE = LocalTime.of(18, 30);

    private final AccountSnapshotRepository snapshots;
    private final TradingDayRepository days;
    private final JobRunRepository jobs;
    private final Clock clock;
    private final ZoneId zone;

    public AccountAuditService(AccountSnapshotRepository snapshots, TradingDayRepository days, JobRunRepository jobs,
                               Clock clock, ZoneId zone) {
        this.snapshots = snapshots;
        this.days = days;
        this.jobs = jobs;
        this.clock = clock;
        this.zone = zone;
    }

    public Report audit(LocalDate date) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        LocalDate d = date != null ? date : expectedDate(now);
        List<Check> checks = new ArrayList<>();
        Map<String, Object> summary = new LinkedHashMap<>();

        if (days.covers(Market.US, d) && !days.isTradingDay(Market.US, d)) {
            summary.put("tradingDay", false);
            checks.add(new Check("calendar", true, true, d + " 美股休市，当天没有账户快照", 0, List.of()));
            return new Report(d, true, clock.instant(), summary, checks);
        }
        summary.put("tradingDay", true);

        List<AccountSnapshotRow> rows = snapshots.on(d);
        summary.put("snapshots", rows.size());
        if (rows.isEmpty()) {
            boolean notDue = !now.isAfter(d.atTime(SNAPSHOT_DUE).atZone(zone));
            // 从来没有过快照 = 刚启用（首份在下一个交易日 18:00），与"停摆了"不是一回事，只提示
            boolean everTaken = snapshots.latest().isPresent();
            String detail = notDue ? d + " 的账户快照定于美东 18:00，还没到核对时点（18:30），先不判失败"
                    : !everTaken ? "还没有任何账户快照（刚启用，首份在下一个交易日美东 18:00），先不判失败"
                    : d + " 没有账户快照：看 ACCOUNT_SNAPSHOT 作业与盈透网关；盈透只给当前状态，过了次日盘前这一天就补不回来";
            checks.add(new Check("snapshotExists", false, !notDue && everTaken, detail, 0, List.of()));
        } else {
            checks.add(new Check("snapshotExists", true, true,
                    d + " 有 " + rows.size() + " 份账户快照（" + rows.stream().map(AccountSnapshotRow::accountMask).collect(Collectors.joining("、")) + "）",
                    rows.size(), List.of()));
            for (AccountSnapshotRow row : rows) {
                String suffix = rows.size() > 1 ? "@" + row.accountMask() : "";
                List<PositionSnapshotRow> positions = snapshots.positions(row.id());
                List<String> notOk = snapshots.reconChecks(row.id()).stream()
                        .filter(c -> !"OK".equals(c.status()))
                        .map(c -> c.name() + " " + c.status() + "：" + c.detail())
                        .toList();
                summary.put("positions" + suffix, positions.size());
                summary.put("reconStatus" + suffix, row.reconStatus());
                checks.add(new Check("reconciliation" + suffix, "OK".equals(row.reconStatus()), "FAIL".equals(row.reconStatus()),
                        "对账 " + row.reconStatus() + (notOk.isEmpty() ? "（四项全部通过）" : "：" + notOk.size() + " 项未通过"),
                        notOk.size(), notOk));
                List<String> unpriced = positions.stream().filter(p -> "NONE".equals(p.priceSource()))
                        .map(PositionSnapshotRow::symbol).sorted().toList();
                long bySnapshot = positions.stream().filter(p -> "SNAPSHOT".equals(p.priceSource())).count();
                checks.add(new Check("pricing" + suffix, unpriced.isEmpty(), false,
                        unpriced.isEmpty() ? positions.size() + " 条持仓都有价（其中富途快照价 " + bySnapshot + " 条）"
                                : unpriced.size() + " 条持仓缺价",
                        unpriced.size(), unpriced));
            }
        }

        Optional<JobRunRow> last = jobs.latestOf(Jobs.ACCOUNT_SNAPSHOT);
        checks.add(new Check("snapshotJob", last.map(j -> "OK".equals(j.status()) || "PARTIAL".equals(j.status())).orElse(false), false,
                last.map(j -> "最近一次账户快照作业 #" + j.id() + " " + j.status() + "（" + j.startedAt() + "）")
                        .orElse("还没有跑过账户快照作业"),
                last.isPresent() ? 1 : 0, List.of()));

        boolean ok = checks.stream().filter(Check::critical).allMatch(Check::ok);
        return new Report(d, ok, clock.instant(), summary, checks);
    }

    LocalDate expectedDate(ZonedDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDate d = DailyIncrementService.expectedLatestTradingDay(days.between(Market.US, today.minusDays(45), today), now);
        return d != null ? d : today;
    }
}
