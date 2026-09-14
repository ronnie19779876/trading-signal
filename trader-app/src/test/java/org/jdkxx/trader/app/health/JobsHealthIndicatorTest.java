package org.jdkxx.trader.app.health;

import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.JobRunRow;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobsHealthIndicatorTest {

    private static final Instant NOW = Instant.parse("2026-09-10T22:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static JobRunRow row(String job, String status, Instant at) {
        return new JobRunRow(1, job, "SCHEDULE", at, at.plusSeconds(60), status, "摘要");
    }

    private static JobsHealthIndicator indicator(JobRunRepository repo) {
        return new JobsHealthIndicator(repo).clock(CLOCK);
    }

    private static JobRunRepository repo(List<JobRunRow> latest, boolean incrementRecent) {
        JobRunRepository r = mock(JobRunRepository.class);
        when(r.latestPerJob()).thenReturn(latest);
        when(r.succeededSince(eq(Jobs.DAILY_INCREMENT), any())).thenReturn(incrementRecent);
        return r;
    }

    @Test
    void 全部正常时为UP() {
        Health h = indicator(repo(List.of(
                row(Jobs.DAILY_INCREMENT, "OK", NOW.minusSeconds(3600)),
                row(Jobs.VALUATION_SNAPSHOT, "OK", NOW.minusSeconds(3000))), true)).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsKey(Jobs.DAILY_INCREMENT);
    }

    @Test
    void 有作业被跳过时降级() {
        // 这正是要抓的场景：估值被增量挡住、重试到底放弃，此前完全没人知道
        Health h = indicator(repo(List.of(
                row(Jobs.DAILY_INCREMENT, "OK", NOW.minusSeconds(3600)),
                row(Jobs.VALUATION_SNAPSHOT, "SKIPPED", NOW.minusSeconds(3000))), true)).health();

        assertThat(h.getStatus()).isEqualTo(GatewaysHealthIndicator.DEGRADED);
        assertThat(h.getDetails().get(Jobs.VALUATION_SNAPSHOT).toString()).startsWith("SKIPPED");
    }

    @Test
    void 有作业失败时降级() {
        Health h = indicator(repo(List.of(
                row(Jobs.DAILY_INCREMENT, "FAILED", NOW.minusSeconds(600))), true)).health();

        assertThat(h.getStatus()).isEqualTo(GatewaysHealthIndicator.DEGRADED);
    }

    @Test
    void 增量长期没成功时降级_即使最近一次记录是OK() {
        // 最近一次是很久以前的 OK：状态看着正常，其实已经停摆
        Health h = indicator(repo(List.of(
                row(Jobs.DAILY_INCREMENT, "OK", NOW.minusSeconds(30 * 86400))), false)).health();

        assertThat(h.getStatus()).isEqualTo(GatewaysHealthIndicator.DEGRADED);
        assertThat(h.getDetails()).containsKey("overdue");
    }

    @Test
    void 补偿检查停摆时降级() {
        // 1.1.x 的盲区：补偿检查数据齐全时不留痕，它自己不跑了没人知道
        JobRunRepository r = repo(List.of(
                row(Jobs.DAILY_INCREMENT, "OK", NOW.minusSeconds(3600)),
                row(Jobs.CATCHUP_CHECK, "OK", NOW.minusSeconds(6 * 86400))), true);
        when(r.succeededSince(eq(Jobs.CATCHUP_CHECK), any())).thenReturn(false);

        Health h = indicator(r).health();

        assertThat(h.getStatus()).isEqualTo(GatewaysHealthIndicator.DEGRADED);
        assertThat(h.getDetails()).containsKey("catchupOverdue");
    }

    @Test
    void 补偿检查正常时不降级() {
        JobRunRepository r = repo(List.of(
                row(Jobs.DAILY_INCREMENT, "OK", NOW.minusSeconds(3600)),
                row(Jobs.CATCHUP_CHECK, "OK", NOW.minusSeconds(600))), true);
        when(r.succeededSince(eq(Jobs.CATCHUP_CHECK), any())).thenReturn(true);

        Health h = indicator(r).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).doesNotContainKey("catchupOverdue");
    }

    @Test
    void 补偿检查刚上线从未跑过不算降级() {
        Health h = indicator(repo(List.of(
                row(Jobs.DAILY_INCREMENT, "OK", NOW.minusSeconds(3600))), true)).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails().get(Jobs.CATCHUP_CHECK)).isEqualTo("从未跑过");
    }

    @Test
    void 补偿检查失败时降级() {
        JobRunRepository r = repo(List.of(
                row(Jobs.DAILY_INCREMENT, "OK", NOW.minusSeconds(3600)),
                row(Jobs.CATCHUP_CHECK, "FAILED", NOW.minusSeconds(600))), true);
        when(r.succeededSince(eq(Jobs.CATCHUP_CHECK), any())).thenReturn(true);

        assertThat(indicator(r).health().getStatus()).isEqualTo(GatewaysHealthIndicator.DEGRADED);
    }

    @Test
    void 账户快照停摆时降级() {
        JobRunRepository r = repo(List.of(
                row(Jobs.DAILY_INCREMENT, "OK", NOW.minusSeconds(3600)),
                row(Jobs.ACCOUNT_SNAPSHOT, "OK", NOW.minusSeconds(6 * 86400))), true);
        when(r.succeededSince(eq(Jobs.ACCOUNT_SNAPSHOT), any())).thenReturn(false);

        Health h = indicator(r).health();

        assertThat(h.getStatus()).isEqualTo(GatewaysHealthIndicator.DEGRADED);
        assertThat(h.getDetails()).containsKey("accountSnapshotOverdue");
    }

    @Test
    void 账户快照从未跑过不算降级() {
        Health h = indicator(repo(List.of(
                row(Jobs.DAILY_INCREMENT, "OK", NOW.minusSeconds(3600))), true)).health();

        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails().get(Jobs.ACCOUNT_SNAPSHOT)).isEqualTo("从未跑过");
    }

    @Test
    void 查库出错也不让健康端点报错() {
        JobRunRepository r = mock(JobRunRepository.class);
        when(r.latestPerJob()).thenThrow(new IllegalStateException("db down"));

        Health h = indicator(r).health();

        assertThat(h.getStatus()).isEqualTo(GatewaysHealthIndicator.DEGRADED);
        assertThat(h.getDetails()).containsKey("error");
    }
}
