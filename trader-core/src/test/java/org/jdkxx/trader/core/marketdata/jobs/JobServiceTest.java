package org.jdkxx.trader.core.marketdata.jobs;

import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 作业结论与结论落库要分开。
 *
 * <p>3.1.1 前 {@code repo.finish} 写在 try 里：作业<b>已经跑成功</b>、只是结果落库时抖了一下，
 * 会被同一个 catch 接住并把这次运行记成 FAILED，巡检与作业健康指标跟着报假警
 * （2026-09-25 全项目审查发现）。
 */
class JobServiceTest {

    private final JobRunRepository repo = mock(JobRunRepository.class);

    /** 等 finish 被调用（作业跑在自己的线程上）。 */
    private CountDownLatch finishLatch(String expectedStatus) {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(inv -> {
            latch.countDown();
            return null;
        }).when(repo).finish(anyLong(), eq(expectedStatus), anyString());
        return latch;
    }

    @Test
    void 结果落库失败不把跑成功的作业记成FAILED() throws Exception {
        when(repo.start(anyString(), anyString())).thenReturn(1L);
        CountDownLatch tried = new CountDownLatch(1);
        doAnswer(inv -> {
            tried.countDown();
            throw new RuntimeException("数据库抖了一下");
        }).when(repo).finish(anyLong(), eq("OK"), anyString());

        JobService s = new JobService(repo);
        s.submit("JOB", "SCHEDULE", ctx -> "跑完了");

        assertThat(tried.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);
        verify(repo, never()).finish(anyLong(), eq("FAILED"), anyString());
        assertThat(s.current()).as("不管落库成不成功，运行位都要放开").isEmpty();
    }

    @Test
    void 作业抛异常记FAILED() throws Exception {
        when(repo.start(anyString(), anyString())).thenReturn(2L);
        CountDownLatch done = finishLatch("FAILED");

        JobService s = new JobService(repo);
        s.submit("JOB", "SCHEDULE", ctx -> {
            throw new IllegalStateException("炸了");
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        verify(repo).finish(eq(2L), eq("FAILED"), contains("炸了"));
    }

    @Test
    void 有partial时记PARTIAL() throws Exception {
        when(repo.start(anyString(), anyString())).thenReturn(3L);
        CountDownLatch done = finishLatch("PARTIAL");

        JobService s = new JobService(repo);
        s.submit("JOB", "MANUAL", ctx -> {
            ctx.partial("有几只失败");
            return "跑完了";
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        verify(repo).finish(eq(3L), eq("PARTIAL"), contains("有几只失败"));
    }
}
