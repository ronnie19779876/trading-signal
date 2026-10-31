package org.jdkxx.trader.app.health;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.JobRunRow;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Health indicator {@code jobs}: are the scheduled batch jobs still doing their work?
 *
 * <p>Before this, a failed job left only a log line, and logs were lost when old deployments were
 * cleaned up, so effectively nobody knew. This exposes the state in machine-readable form for the
 * daily check script and any future external monitoring.
 *
 * <p>DEGRADED (still HTTP 200, consistent with {@code gateways}) when:
 *
 * <ul>
 *   <li>the latest run of any watched job is FAILED or SKIPPED;
 *   <li>the daily increment is overdue: no success for more than {@link #OVERDUE} with a
 *       trading-day close in between;
 *   <li>the catch-up check has stalled: it has run before but has not succeeded for more than
 *       {@link #OVERDUE} (it writes a row every weekday, including non-trading days; never having
 *       run right after go-live does not count);
 *   <li>the account snapshot has stalled: it has run before but has not succeeded for more than
 *       {@link #ACCOUNT_OVERDUE} (it only runs on trading days, and a long weekend from Friday to
 *       Tuesday is exactly 96 hours, hence the wider threshold). The same threshold applies to the
 *       signal evaluation.
 * </ul>
 *
 * Only wired on instances with scheduling enabled: a dev instance runs no batch jobs, so "overdue"
 * is meaningless there.
 */
@Component("jobs")
@ConditionalOnProperty(name = "trader.marketdata.schedule-enabled", havingValue = "true")
public class JobsHealthIndicator implements HealthIndicator {

  /**
   * The daily increment counts as overdue after this long without a success. Safe across weekends
   * and holidays (Friday's run to Monday's close is less than 96 hours).
   */
  static final Duration OVERDUE = Duration.ofHours(96);

  /**
   * Overdue threshold for the account snapshot and signal evaluation (they only run on trading
   * days; Friday to Tuesday over a long weekend is exactly 96 hours).
   */
  static final Duration ACCOUNT_OVERDUE = Duration.ofHours(120);

  /** Jobs whose latest run is checked for failure. */
  private static final List<String> WATCHED =
      List.of(
          Jobs.DAILY_INCREMENT,
          Jobs.VALUATION_SNAPSHOT,
          Jobs.UNIVERSE_SYNC,
          Jobs.FINANCIALS_REFRESH,
          Jobs.CATCHUP_CHECK,
          Jobs.ACCOUNT_SNAPSHOT,
          Jobs.SIGNAL_EVALUATION);

  private final JobRunRepository jobs;

  /**
   * Keep <b>one</b> constructor only: with two public constructors Spring cannot decide which to
   * use, falls back to looking for a no-arg constructor and fails at startup. This bean is only
   * wired on instances with scheduling enabled, so the failure never shows up locally. The clock is
   * therefore a field that tests can replace.
   */
  private Clock clock = Clock.systemUTC();

  public JobsHealthIndicator(JobRunRepository jobs) {
    this.jobs = jobs;
  }

  /** For tests: fixed clock. */
  JobsHealthIndicator clock(Clock clock) {
    this.clock = clock;
    return this;
  }

  @Override
  public Health health() {
    Health.Builder b = Health.up();
    boolean degraded = false;
    try {
      Map<String, JobRunRow> latest = new LinkedHashMap<>();
      for (JobRunRow r : jobs.latestPerJob()) {
        latest.put(r.job(), r);
      }
      for (String job : WATCHED) {
        JobRunRow r = latest.get(job);
        if (r == null) {
          b.withDetail(job, "从未跑过");
          continue;
        }
        b.withDetail(job, r.status() + " @ " + r.startedAt() + summary(r));
        if ("FAILED".equals(r.status()) || "SKIPPED".equals(r.status())) {
          degraded = true;
        }
      }
      Instant since = clock.instant().minus(OVERDUE);
      if (!jobs.succeededSince(Jobs.DAILY_INCREMENT, since)) {
        b.withDetail("overdue", "每日增量超过 " + OVERDUE.toHours() + " 小时没有成功跑过");
        degraded = true;
      }
      if (latest.containsKey(Jobs.CATCHUP_CHECK)
          && !jobs.succeededSince(Jobs.CATCHUP_CHECK, since)) {
        b.withDetail("catchupOverdue", "补偿检查超过 " + OVERDUE.toHours() + " 小时没有成功跑过");
        degraded = true;
      }
      if (latest.containsKey(Jobs.ACCOUNT_SNAPSHOT)
          && !jobs.succeededSince(Jobs.ACCOUNT_SNAPSHOT, clock.instant().minus(ACCOUNT_OVERDUE))) {
        b.withDetail("accountSnapshotOverdue", "账户快照超过 " + ACCOUNT_OVERDUE.toHours() + " 小时没有成功跑过");
        degraded = true;
      }
      if (latest.containsKey(Jobs.SIGNAL_EVALUATION)
          && !jobs.succeededSince(Jobs.SIGNAL_EVALUATION, clock.instant().minus(ACCOUNT_OVERDUE))) {
        b.withDetail(
            "signalEvaluationOverdue", "信号评估超过 " + ACCOUNT_OVERDUE.toHours() + " 小时没有成功跑过");
        degraded = true;
      }
    } catch (RuntimeException e) {
      // Failing to query is a problem in itself, but must not turn the health endpoint into a 500.
      return b.status(GatewaysHealthIndicator.DEGRADED).withDetail("error", e.toString()).build();
    }
    return degraded ? b.status(GatewaysHealthIndicator.DEGRADED).build() : b.build();
  }

  private static String summary(JobRunRow r) {
    if (r.summary() == null || r.summary().isBlank()) {
      return "";
    }
    String s = r.summary().split("；")[0];
    return "：" + (s.length() > 80 ? s.substring(0, 80) + "…" : s);
  }
}
