package org.jdkxx.trader.core.selection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdkxx.trader.domain.selection.Factor;
import org.jdkxx.trader.domain.selection.Metric;
import org.jdkxx.trader.domain.selection.Rating;
import org.jdkxx.trader.domain.selection.StockInputs;
import org.jdkxx.trader.domain.selection.StockScore;
import org.jdkxx.trader.storage.selection.SelectionScoreRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SelectionScoringServiceTest {

    private final SelectionScoringService service = new SelectionScoringService(mock(SelectionDataLoader.class),
            mock(SelectionScoreRepository.class), mock(org.jdkxx.trader.storage.marketdata.TradingDayRepository.class),
            () -> LocalDate.of(2026, 10, 9), new ObjectMapper());

    private static StockInputs in(long id, double roe) {
        return new StockInputs(id, "S" + id, "IT", null, null, Map.of(Metric.ROE, roe), EnumSet.allOf(Metric.class));
    }

    private static StockScore score(long id, Rating rating) {
        return new StockScore(id, "S" + id, "IT", null, null, Map.of(Metric.ROE, 0.2), Map.of(Metric.ROE, 80.0),
                Map.of(Factor.VALUE, 70.0), rating == null ? null : 75.0, rating == null ? null : 90.0, rating);
    }

    /** 连续 Strong Buy：接着前一天加一；当天不是 Strong Buy 就归零。 */
    @Test
    void 连续StrongBuy天数接续与归零() {
        List<SelectionScoreRepository.ScoreRow> rows = service.rows(LocalDate.of(2026, 10, 9), List.of(in(1, 0.2), in(2, 0.1), in(3, 0.3)),
                List.of(score(1, Rating.STRONG_BUY), score(2, Rating.BUY), score(3, Rating.STRONG_BUY)), Map.of(1L, 4, 2L, 7));

        assertThat(rows.get(0).sbStreak()).isEqualTo(5);
        assertThat(rows.get(1).sbStreak()).isZero();
        assertThat(rows.get(2).sbStreak()).isEqualTo(1);
    }

    /** 中段重放后，截止日之后已有的评分日连续天数会过期：作业要标部分完成并提示把 to 延长（2026-10-12 实测 TRV、WDC）。 */
    @Test
    void 中段重放提示之后的连续天数没重算() {
        SelectionDataLoader loader = mock(SelectionDataLoader.class);
        SelectionScoreRepository scores = mock(SelectionScoreRepository.class);
        org.jdkxx.trader.storage.marketdata.TradingDayRepository days = mock(org.jdkxx.trader.storage.marketdata.TradingDayRepository.class);
        LocalDate d = LocalDate.of(2026, 9, 1);
        org.mockito.Mockito.when(days.between(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of(d));
        org.mockito.Mockito.when(loader.load(d, d)).thenReturn(new SelectionDataset(List.of()));
        org.mockito.Mockito.when(scores.latestBefore(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(scores.latestDate("F1")).thenReturn(java.util.Optional.of(LocalDate.of(2026, 9, 16)));
        List<String> partials = new java.util.ArrayList<>();
        SelectionScoringService s = new SelectionScoringService(loader, scores, days, () -> LocalDate.of(2026, 10, 9), new ObjectMapper());

        s.replay(d, d, new org.jdkxx.trader.core.marketdata.jobs.JobContext() {
            public long id() { return 1; }
            public void progress(String text) { }
            public void partial(String reason) { partials.add(reason); }
            public boolean cancelled() { return false; }
        });

        assertThat(partials).anyMatch(p -> p.contains("2026-09-16") && p.contains("连续"));
    }

    @Test
    void 指标明细为原值与百分位() {
        String json = service.metricsJson(score(1, Rating.BUY));

        assertThat(json).isEqualTo("{\"ROE\":[0.2,80.0]}");
    }

    /** 指纹只随输入变：同样输入同样指纹，任一指标原值变了指纹就变。 */
    @Test
    void 指纹随输入变化() {
        assertThat(SelectionScoringService.fingerprint(in(1, 0.2))).isEqualTo(SelectionScoringService.fingerprint(in(1, 0.2)))
                .hasSize(32).isNotEqualTo(SelectionScoringService.fingerprint(in(1, 0.2000001)));
    }
}
