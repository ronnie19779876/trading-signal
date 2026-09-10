package org.jdkxx.trader.app.health;

import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.JobRunRow;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健康指标 {@code jobs}：定时跑批是不是还在正常干活。
 *
 * <p>此前作业失败只有一行日志，日志还会随部署清理丢掉，等于没人知道。
 * 这里把状态暴露成机器可读，巡检脚本与将来任何外部监控都能用。
 *
 * <p>DEGRADED（HTTP 仍 200，与 {@code gateways} 一致）的条件：
 * <ul>
 *   <li>任一作业最近一次是 FAILED 或 SKIPPED；</li>
 *   <li>每日增量逾期未跑——距上次成功超过 {@link #OVERDUE} 且期间有过交易日收盘。</li>
 * </ul>
 * 只在开了定时跑批的实例上装配：开发实例不跑批，报"逾期"没有意义。
 */
@Component("jobs")
@ConditionalOnProperty(name = "trader.marketdata.schedule-enabled", havingValue = "true")
public class JobsHealthIndicator implements HealthIndicator {

    /** 每日增量超过这么久没成功就算逾期。跨过周末与假日仍安全（周五跑完到周一收盘不足 96 小时）。 */
    static final Duration OVERDUE = Duration.ofHours(96);

    /** 参与"最近一次是否失败"判断的作业。 */
    private static final List<String> WATCHED = List.of(
            Jobs.DAILY_INCREMENT, Jobs.VALUATION_SNAPSHOT, Jobs.UNIVERSE_SYNC, Jobs.FINANCIALS_REFRESH);

    private final JobRunRepository jobs;
    /**
     * 只保留<b>一个</b>构造器：两个公开构造器会让 Spring 找不到该用哪个，
     * 转而去找无参构造器并在启动时炸掉——而这个 bean 只在开了跑批的实例装配，本地根本发现不了。
     * 时钟改成测试可替换的字段。
     */
    private Clock clock = Clock.systemUTC();

    public JobsHealthIndicator(JobRunRepository jobs) {
        this.jobs = jobs;
    }

    /** 测试用：固定时钟。 */
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
        } catch (RuntimeException e) {
            // 查不出来本身就是问题，但别让健康端点 500
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
