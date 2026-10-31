package org.jdkxx.trader.core.marketdata.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.junit.jupiter.api.Test;

class ScheduledSubmitterTest {

  /**
   * The SKIPPED trace must record the <b>real</b> trigger source. record() used to hard-code the
   * trigger as "SCHEDULE", so a job submitted by the catch-up check (CATCHUP) that was given up on
   * was also recorded as a scheduled trigger, and afterwards nobody could tell which path it came
   * from (found in the 2026-09-25 full-project review).
   */
  @Test
  void 一直被占用时重试到底写SKIPPED_并记下真实触发来源() throws Exception {
    JobRunRepository runs = mock(JobRunRepository.class);
    CountDownLatch skipped = new CountDownLatch(1);
    when(runs.skipped(any(), any(), any()))
        .thenAnswer(
            inv -> {
              skipped.countDown();
              return 1L;
            });
    AtomicInteger tries = new AtomicInteger();

    try (ScheduledSubmitter s = new ScheduledSubmitter(runs, "test-retry", Duration.ofMillis(10))) {
      s.submit(
          "测试作业",
          "JOB",
          "CATCHUP",
          () -> {
            tries.incrementAndGet();
            throw new IllegalStateException("作业「X」正在运行");
          });
      assertThat(skipped.await(5, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(tries).hasValue(ScheduledSubmitter.RETRY_TIMES);
    verify(runs).skipped(eq("JOB"), eq("CATCHUP"), contains("正在运行"));
    verify(runs, never()).skipped(any(), eq("SCHEDULE"), any());
  }

  @Test
  void 重试途中提交成功就不写SKIPPED() throws Exception {
    JobRunRepository runs = mock(JobRunRepository.class);
    CountDownLatch submitted = new CountDownLatch(1);
    AtomicInteger tries = new AtomicInteger();

    try (ScheduledSubmitter s = new ScheduledSubmitter(runs, "test-retry", Duration.ofMillis(10))) {
      s.submit(
          "测试作业",
          "JOB",
          "SCHEDULE",
          () -> {
            if (tries.incrementAndGet() < 3) {
              throw new IllegalStateException("busy");
            }
            submitted.countDown();
            return 42L;
          });
      assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(tries).hasValue(3);
    verify(runs, never()).skipped(any(), any(), any());
  }
}
