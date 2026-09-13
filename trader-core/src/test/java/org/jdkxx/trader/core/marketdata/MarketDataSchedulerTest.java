package org.jdkxx.trader.core.marketdata;

import org.jdkxx.trader.core.marketdata.fundamentals.FundamentalsFacade;
import org.jdkxx.trader.core.marketdata.jobs.CatchUpService;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 当天补偿检查的留痕：每次运行都要在 job_run 里写一行，否则它自己停摆时没人知道。
 */
class MarketDataSchedulerTest {

    private static final LocalDate THU = LocalDate.of(2026, 9, 10);

    private final MarketDataFacade facade = mock(MarketDataFacade.class);
    private final FundamentalsFacade fundamentals = mock(FundamentalsFacade.class);
    private final JobRunRepository jobRuns = mock(JobRunRepository.class);
    private final CatchUpService catchUp = mock(CatchUpService.class);
    private final MarketDataScheduler scheduler = new MarketDataScheduler(facade, fundamentals, jobRuns, catchUp);

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    @Test
    void 数据齐全也写一行运行记录() {
        when(catchUp.check()).thenReturn(new CatchUpService.Gap(THU, true, 2, 2, 2));

        scheduler.catchUp();

        verify(jobRuns).record(eq(Jobs.CATCHUP_CHECK), eq("SCHEDULE"), eq("OK"), contains("都齐了"));
        verifyNoInteractions(facade, fundamentals);
    }

    @Test
    void 非交易日也写一行_健康指标靠它判断检查还活着() {
        when(catchUp.check()).thenReturn(new CatchUpService.Gap(THU.minusDays(1), false, 0, 0, 0));

        scheduler.catchUp();

        verify(jobRuns).record(eq(Jobs.CATCHUP_CHECK), eq("SCHEDULE"), eq("OK"), contains("非交易日"));
        verifyNoInteractions(facade, fundamentals);
    }

    @Test
    void 发现缺口时写记录并提交补跑() {
        when(catchUp.check()).thenReturn(new CatchUpService.Gap(THU, true, 2, 2, 0));

        scheduler.catchUp();

        verify(jobRuns).record(eq(Jobs.CATCHUP_CHECK), eq("SCHEDULE"), eq("OK"), contains("估值快照"));
        verify(fundamentals).refreshValuation("CATCHUP");
        verify(facade, never()).increment(anyString());
    }

    @Test
    void 检查本身出错记FAILED() {
        when(catchUp.check()).thenThrow(new IllegalStateException("db down"));

        scheduler.catchUp();

        verify(jobRuns).record(eq(Jobs.CATCHUP_CHECK), eq("SCHEDULE"), eq("FAILED"), contains("db down"));
    }

    @Test
    void 写记录失败不能挡住补跑() {
        when(catchUp.check()).thenReturn(new CatchUpService.Gap(THU, true, 2, 1, 0));
        doThrow(new IllegalStateException("db down")).when(jobRuns).record(any(), any(), any(), any());

        scheduler.catchUp();

        verify(facade).increment("CATCHUP");
        verify(fundamentals).refreshValuation("CATCHUP");
    }
}
