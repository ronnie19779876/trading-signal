package org.jdkxx.trader.core.signal;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.domain.signal.SignalSuppression.PreviousDay;
import org.jdkxx.trader.storage.signal.SignalEvaluationRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SignalEvaluationServiceTest {

    private static final Instrument X = Instrument.us("X");
    private static final LocalDate PREV = LocalDate.of(2026, 9, 1);

    private final SignalEvaluationService service =
            new SignalEvaluationService(null, null, null, null, null, null, null, null, null, null, null);

    private static SignalEvaluationRow stored(String status, String gates, String outcome) {
        return new SignalEvaluationRow(1, "X", PREV, "sentinel-v1", status, null, outcome, gates, 0, null, "POOL",
                null, null, null, null, null, null, "f", null, null, null);
    }

    @Test
    void 有正常判定的评估行时按它判断上一交易日() {
        assertThat(service.previousDay(stored("EVALUATED", "PPPP", "SIGNAL"), List.of(), List.of(), Set(), PREV))
                .isEqualTo(PreviousDay.PASSED);
        assertThat(service.previousDay(stored("EVALUATED", "PPPP", "SUPPRESSED_COOLDOWN"), List.of(), List.of(), Set(), PREV))
                .isEqualTo(PreviousDay.PASSED);
        assertThat(service.previousDay(stored("EVALUATED", "PPPP", "BLOCKED_BY_AI"), List.of(), List.of(), Set(), PREV))
                .isEqualTo(PreviousDay.BLOCKED_BY_AI);
        assertThat(service.previousDay(stored("EVALUATED", "PFPP", "NO_SIGNAL"), List.of(), List.of(), Set(), PREV))
                .isEqualTo(PreviousDay.NOT_PASSED);
    }

    /** 守护：上一交易日没有评估行（刚上线、漏跑）或当天不予判定时，不能直接当成"没过"——那会让连续满足的第二天重复发信号。 */
    @Test
    void 没有评估行或当天不予判定时现场重算() {
        // 合成行情：先涨后围绕 155 摆动形成支撑区，最后一根回踩支撑区放量收阳 → 上一交易日四门全过
        List<LocalDate> days = new ArrayList<>();
        List<DailyBar> raw = new ArrayList<>();
        LocalDate d = LocalDate.of(2025, 6, 2);
        for (int i = 0; i < 300; i++) {
            while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                d = d.plusDays(1);
            }
            boolean last = i == 299;
            double p = i < 200 ? 100 + 0.25 * i : 155 + 5 * Math.sin(2 * Math.PI * (i - 200) / 20.0);
            double open = last ? 149.5 : p;
            double close = last ? 152 : p;
            double low = last ? 148.5 : p - 1;
            double high = last ? 152.5 : p + 1;
            raw.add(new DailyBar(X, d, bd(open), bd(high), bd(low), bd(close), null, last ? 3_000_000 : 1_000_000,
                    null, null, null, null, false));
            days.add(d);
            d = d.plusDays(1);
        }
        LocalDate prev = days.get(days.size() - 1);
        HashSet<LocalDate> calendar = new HashSet<>(days);

        assertThat(service.previousDay(null, raw, List.of(), calendar, prev)).isEqualTo(PreviousDay.PASSED);
        assertThat(service.previousDay(stored("SKIPPED_STALE_DATA", null, null), raw, List.of(), calendar, prev))
                .isEqualTo(PreviousDay.PASSED);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    /** 守护：模型否决的候选写成 BLOCKED_BY_AI 评估 + VETOED 信号（照样进账本）；否决信息关联到信号。 */
    @Test
    void 模型否决时信号记为VETOED且评估记BLOCKED_BY_AI() {
        org.jdkxx.trader.storage.signal.SignalStore store = org.mockito.Mockito.mock(org.jdkxx.trader.storage.signal.SignalStore.class);
        org.jdkxx.trader.core.signal.ai.AiVetoService ai = org.mockito.Mockito.mock(org.jdkxx.trader.core.signal.ai.AiVetoService.class);
        org.mockito.Mockito.when(ai.analyze(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("SIGNAL_VETO"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new org.jdkxx.trader.core.signal.ai.AiVetoService.Outcome(
                        org.jdkxx.trader.domain.signal.SignalSuppression.AiVerdict.VETO, 42L, "AVOID"));
        org.mockito.Mockito.when(store.save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(11L);
        SignalEvaluationService svc = new SignalEvaluationService(null, null, null, null, null, null, store, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), ai);

        Series s = passingSeries();
        var instrument = new org.jdkxx.trader.storage.marketdata.InstrumentRow(1, org.jdkxx.trader.domain.Market.US, "X", "X", null,
                org.jdkxx.trader.domain.SecurityType.STOCK, 1, null, false, null, null, "RESOLVED");
        List<LocalDate> cal = new ArrayList<>(s.days());
        SignalEvaluationService.Result r = svc.evaluate(instrument, s.raw(), List.of(), s.last(), cal.get(cal.size() - 2),
                new HashSet<>(cal), cal, null, null, "POOL", "LIVE", s.last().plusDays(3), 5L, new ArrayList<>(),
                new SignalEvaluationService.AiContext(true, null));

        assertThat(r.outcome()).isEqualTo(org.jdkxx.trader.domain.signal.SignalSuppression.Outcome.BLOCKED_BY_AI);
        var evalCaptor = org.mockito.ArgumentCaptor.forClass(SignalEvaluationRow.class);
        var signalCaptor = org.mockito.ArgumentCaptor.forClass(org.jdkxx.trader.storage.signal.EntrySignalRow.class);
        org.mockito.Mockito.verify(store).save(evalCaptor.capture(), signalCaptor.capture(), org.mockito.ArgumentMatchers.any());
        assertThat(evalCaptor.getValue().outcome()).isEqualTo("BLOCKED_BY_AI");
        assertThat(signalCaptor.getValue().status()).isEqualTo("VETOED");
        assertThat(signalCaptor.getValue().aiAnalysisId()).isEqualTo(42L);
        assertThat(signalCaptor.getValue().aiStance()).isEqualTo("AVOID");
        org.mockito.Mockito.verify(ai).linkSignal(42L, 11L);
    }

    /** 守护：AI 关着（补跑或开关关闭）时不调模型，候选直接成为信号。 */
    @Test
    void AI关闭时不调模型() {
        org.jdkxx.trader.storage.signal.SignalStore store = org.mockito.Mockito.mock(org.jdkxx.trader.storage.signal.SignalStore.class);
        org.jdkxx.trader.core.signal.ai.AiVetoService ai = org.mockito.Mockito.mock(org.jdkxx.trader.core.signal.ai.AiVetoService.class);
        SignalEvaluationService svc = new SignalEvaluationService(null, null, null, null, null, null, store, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(), ai);
        Series s = passingSeries();
        var instrument = new org.jdkxx.trader.storage.marketdata.InstrumentRow(1, org.jdkxx.trader.domain.Market.US, "X", "X", null,
                org.jdkxx.trader.domain.SecurityType.STOCK, 1, null, false, null, null, "RESOLVED");
        List<LocalDate> cal = new ArrayList<>(s.days());

        SignalEvaluationService.Result r = svc.evaluate(instrument, s.raw(), List.of(), s.last(), cal.get(cal.size() - 2),
                new HashSet<>(cal), cal, null, null, "POOL", "BACKFILL", s.last().plusDays(3), 5L, new ArrayList<>(),
                SignalEvaluationService.AiContext.OFF);

        assertThat(r.outcome()).isEqualTo(org.jdkxx.trader.domain.signal.SignalSuppression.Outcome.SIGNAL);
        org.mockito.Mockito.verifyNoInteractions(ai);
    }

    /**
     * 守护：评估顺序就是 AI 额度的分配顺序（边评估边调模型，每日上限先到先得）。
     * 持仓 → 池 → 池外，同一角色内按代码；基准不评估。3.0.0 按成分股的字母序发额度，四巫日持仓 IBKR 被预算跳过。
     */
    @Test
    void 评估目标按持仓池池外排序且排除基准() {
        org.jdkxx.trader.core.marketdata.universe.UniverseScope scope =
                org.mockito.Mockito.mock(org.jdkxx.trader.core.marketdata.universe.UniverseScope.class);
        // 成分股按代码返回（instrument 表 ORDER BY symbol），持仓 IBKR 与池成员 AMZN 夹在中间，基准 SPY 也混在里面
        org.mockito.Mockito.when(scope.universe()).thenReturn(List.of(row(1, "AAA"), row(2, "AMZN"), row(3, "BBB"),
                row(4, "IBKR"), row(5, "SPY"), row(6, "ZZZ")));
        // 池与持仓（已去掉基准）：另有不在成分股里的池成员 TSM 与持仓 BRK.B
        org.mockito.Mockito.when(scope.candidates()).thenReturn(List.of(row(2, "AMZN"), row(4, "IBKR"), row(7, "TSM"), row(8, "BRK.B")));
        org.mockito.Mockito.when(scope.benchmarks()).thenReturn(List.of(row(5, "SPY"), row(9, "QQQ")));
        org.mockito.Mockito.when(scope.roles()).thenReturn(java.util.Map.of(2L, PoolRole.POOL, 4L, PoolRole.HOLDING,
                7L, PoolRole.POOL, 8L, PoolRole.HOLDING, 5L, PoolRole.BENCHMARK, 9L, PoolRole.BENCHMARK));
        SignalEvaluationService svc = new SignalEvaluationService(scope, null, null, null, null, null, null, null, null, null, null);

        assertThat(svc.targets().values()).extracting(org.jdkxx.trader.storage.marketdata.InstrumentRow::symbol)
                .containsExactly("BRK.B", "IBKR", "AMZN", "TSM", "AAA", "BBB", "ZZZ");
    }

    private static org.jdkxx.trader.storage.marketdata.InstrumentRow row(long id, String symbol) {
        return new org.jdkxx.trader.storage.marketdata.InstrumentRow(id, org.jdkxx.trader.domain.Market.US, symbol, symbol, null,
                org.jdkxx.trader.domain.SecurityType.STOCK, 1, null, false, null, null, "RESOLVED");
    }

    record Series(List<DailyBar> raw, List<LocalDate> days, LocalDate last) {
    }

    /** 先涨后围绕 155 摆动形成支撑区，最后一根回踩放量收阳：四门全过；倒数第二根不过（边沿成立）。 */
    private static Series passingSeries() {
        List<LocalDate> days = new ArrayList<>();
        List<DailyBar> raw = new ArrayList<>();
        LocalDate d = LocalDate.of(2025, 6, 2);
        for (int i = 0; i < 300; i++) {
            while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                d = d.plusDays(1);
            }
            boolean last = i == 299;
            double p = i < 200 ? 100 + 0.25 * i : 155 + 5 * Math.sin(2 * Math.PI * (i - 200) / 20.0);
            raw.add(new DailyBar(X, d, bd(last ? 149.5 : p), bd(last ? 152.5 : p + 1), bd(last ? 148.5 : p - 1), bd(last ? 152 : p),
                    null, last ? 3_000_000 : 1_000_000, null, null, null, null, false));
            days.add(d);
            d = d.plusDays(1);
        }
        return new Series(raw, days, days.get(days.size() - 1));
    }

    @Test
    void 冷却按交易日数计() {
        List<LocalDate> cal = List.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3),
                LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 8));
        assertThat(SignalEvaluationService.tradingDaysSince(cal, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8))).isEqualTo(4);
        assertThat(SignalEvaluationService.tradingDaysSince(cal, null, LocalDate.of(2026, 9, 8))).isNull();
        assertThat(SignalEvaluationService.tradingDaysSince(cal, LocalDate.of(2020, 1, 2), LocalDate.of(2026, 9, 8))).isNull();
    }

    @Test
    void 角色只分池持仓与池外() {
        assertThat(SignalEvaluationService.roleOf(PoolRole.HOLDING)).isEqualTo("HOLDING");
        assertThat(SignalEvaluationService.roleOf(PoolRole.BENCHMARK)).isEqualTo("UNIVERSE");
        assertThat(SignalEvaluationService.roleOf(null)).isEqualTo("UNIVERSE");
    }

    private static java.util.Set<LocalDate> Set() {
        return java.util.Set.of();
    }
}
