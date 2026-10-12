package org.jdkxx.trader.core.selection;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.selection.SelectionScoreRepository;
import org.jdkxx.trader.storage.selection.SelectionSimRepository;
import org.jdkxx.trader.storage.selection.SimTradeRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SelectionSimServiceTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    /** 评分日（周一） */
    private static final LocalDate D = LocalDate.of(2026, 8, 3);

    private final SelectionSimRepository sim = mock(SelectionSimRepository.class);
    private final SelectionScoreRepository scores = mock(SelectionScoreRepository.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final DailyBarRepository bars = mock(DailyBarRepository.class);
    private final RehabFactorRepository rehabs = mock(RehabFactorRepository.class);
    private final TradingDayRepository days = mock(TradingDayRepository.class);
    private final JobContext ctx = new JobContext() {
        public long id() { return 1; }
        public void progress(String text) { }
        public void partial(String reason) { }
        public boolean cancelled() { return false; }
    };

    private SelectionSimService service(Instant now) {
        when(sim.account()).thenReturn(new SelectionSimRepository.Account("sim-v1", BigDecimal.valueOf(500_000), now));
        return new SelectionSimService(sim, scores, mock(SelectionTargets.class), instruments, bars, rehabs, days, () -> D.plusDays(30),
                new ObjectMapper().findAndRegisterModules(), Clock.fixed(now, ET), ET);
    }

    private static InstrumentRow row(long id, String symbol) {
        return new InstrumentRow(id, Market.US, symbol, symbol, null, SecurityType.STOCK, 1, null, false, "NASDAQ", id, "RESOLVED");
    }

    private static SelectionScoreRepository.ScoreView score(long id, String symbol, double composite) {
        return new SelectionScoreRepository.ScoreView(D, id, symbol, "Industrials", null, null, null, null, null, null,
                BigDecimal.valueOf(composite), null, "STRONG_BUY", 1, "{}", "f", Instant.EPOCH);
    }

    private void top(int n) {
        List<SelectionScoreRepository.ScoreView> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(score(i, "S" + i, 100 - i));
        }
        when(scores.latestDate(any())).thenReturn(Optional.of(D));
        when(scores.day(any(), eq(D), any(), any())).thenReturn(list);
    }

    @Test
    void 不在前10名不能加() {
        top(12);
        SelectionSimService s = service(Instant.parse("2026-08-03T23:00:00Z"));
        assertThatThrownBy(() -> s.add("S11")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("前 10 名");
    }

    /** 名次变了以后，进行中的笔数可以是已经掉出前 10 的标的：池满时前 10 里没持有的也不能再加。 */
    @Test
    void 池满不能加() {
        top(10);
        when(instruments.findById(3L)).thenReturn(Optional.of(row(3, "S3")));
        when(sim.activeFor(3L)).thenReturn(Optional.empty());
        when(sim.activeCount()).thenReturn(10);
        SelectionSimService s = service(Instant.parse("2026-08-03T23:00:00Z"));
        assertThatThrownBy(() -> s.add("S3")).isInstanceOf(IllegalStateException.class).hasMessageContaining("已满");
    }

    @Test
    void 开盘前点击当天开盘_开盘后点击下一个交易日() {
        LocalDate tue = D.plusDays(1);
        when(days.between(eq(Market.US), any(), any())).thenAnswer(inv -> {
            LocalDate from = inv.getArgument(1);
            return List.of(D, tue, D.plusDays(2)).stream().filter(x -> !x.isBefore(from)).toList();
        });
        SelectionSimService s = service(Instant.EPOCH);
        assertThat(s.nextOpenDate(Instant.parse("2026-08-03T13:29:00Z"))).isEqualTo(D);      // 09:29 ET
        assertThat(s.nextOpenDate(Instant.parse("2026-08-03T13:30:00Z"))).isEqualTo(tue);    // 09:30 ET
        assertThat(s.nextOpenDate(Instant.parse("2026-08-03T22:00:00Z"))).isEqualTo(tue);
    }

    @Test
    void 日历没覆盖未来时跳过周末() {
        when(days.between(eq(Market.US), any(), any())).thenReturn(List.of());
        SelectionSimService s = service(Instant.EPOCH);
        // 周五 18:00 ET → 下周一
        assertThat(s.nextOpenDate(Instant.parse("2026-08-07T22:00:00Z"))).isEqualTo(LocalDate.of(2026, 8, 10));
    }

    private static SimTradeRow pending(long id, LocalDate plannedEntry, Instant requestedAt) {
        SimTradeRow p = SimTradeRow.pending("sim-v1", id, "F1", D, (int) id, "STRONG_BUY", BigDecimal.TEN, requestedAt,
                BigDecimal.valueOf(100), BigDecimal.valueOf(2), BigDecimal.valueOf(96), BigDecimal.valueOf(96), "ATR", null, plannedEntry);
        return withId(p, id, "S" + id);
    }

    private static SimTradeRow withId(SimTradeRow t, long id, String symbol) {
        return new SimTradeRow(id, t.rulesVersion(), t.instrumentId(), symbol, t.modelVersion(), t.scoreDate(), t.scoreRank(), t.rating(),
                t.composite(), t.requestedAt(), t.setupClose(), t.setupAtr(), t.formulaStop(), t.stop(), t.stopLeg(), t.resistance(),
                t.plannedEntryDate(), t.status(), t.entryDate(), t.entryPrice(), t.shares(), t.plusOneR(), t.target(), t.targetSource(),
                t.touchedPlusOneR(), t.targetHitDate(), t.activeStop(), t.chandelierActive(), t.closeRequestedAt(), t.plannedExitDate(),
                t.closeKind(), t.closeNote(), t.exitDate(), t.exitPrice(), t.exitReason(), t.pnl(), t.returnPct(), t.rMultiple(), t.mfeR(),
                t.maeR(), t.barsHeld(), t.lastClose(), t.lastCloseDate(), t.unrealizedPnl(), t.spyReturn(), t.excessReturn(), t.shadowStatus(),
                t.shadowExitDate(), t.shadowExitPrice(), t.shadowExitReason(), t.shadowPnl(), t.shadowReturn(), t.shadowLastClose(),
                t.cancelReason(), t.updatedThrough(), t.updatedAt());
    }

    @Test
    void 入场开盘之前平仓等于撤销() {
        when(days.between(eq(Market.US), any(), any())).thenReturn(List.of());
        SimTradeRow p = pending(1, D.plusDays(1), Instant.parse("2026-08-03T22:00:00Z"));
        when(sim.find(1L)).thenReturn(Optional.of(p));
        SelectionSimService s = service(Instant.parse("2026-08-04T02:00:00Z"));     // 周一 22:00 ET：入场是周二开盘，还没到
        s.requestClose(1, null);
        ArgumentCaptor<SimTradeRow> c = ArgumentCaptor.forClass(SimTradeRow.class);
        verify(sim).update(c.capture());
        assertThat(c.getValue().status()).isEqualTo("CANCELLED");
        assertThat(c.getValue().closeRequestedAt()).isNull();
    }

    /** 已过入场开盘、作业还没把它记成持有：不能撤销（那个开盘已经「成交」了），记平仓请求，下一个开盘平。 */
    @Test
    void 过了入场开盘但还没记成持有_记平仓请求而不是撤销() {
        when(days.between(eq(Market.US), any(), any())).thenReturn(List.of());
        SimTradeRow p = pending(1, D.plusDays(1), Instant.parse("2026-08-03T22:00:00Z"));
        when(sim.find(1L)).thenReturn(Optional.of(p));
        SelectionSimService s = service(Instant.parse("2026-08-04T15:00:00Z"));     // 周二 11:00 ET
        s.requestClose(1, "x");
        ArgumentCaptor<SimTradeRow> c = ArgumentCaptor.forClass(SimTradeRow.class);
        verify(sim).update(c.capture());
        assertThat(c.getValue().status()).isEqualTo("PENDING_ENTRY");
        assertThat(c.getValue().plannedExitDate()).isEqualTo(D.plusDays(2));
        assertThat(c.getValue().closeKind()).isEqualTo("MANUAL");
    }

    /** 评分日前 40 根平稳 K 线（收盘 100），之后两根：入场日开盘 100。 */
    private static List<DailyBar> flat(Instrument inst) {
        List<DailyBar> out = new ArrayList<>();
        for (int i = 40; i >= 0; i--) {
            out.add(bar(inst, D.minusDays(i), 100));
        }
        out.add(bar(inst, D.plusDays(1), 100));
        return out;
    }

    private static DailyBar bar(Instrument inst, LocalDate d, double c) {
        BigDecimal x = BigDecimal.valueOf(c);
        return new DailyBar(inst, d, x, x.add(BigDecimal.ONE), x.subtract(BigDecimal.ONE), x, x, 1000, null, null, null, null, false);
    }

    /**
     * 同一天开盘两笔待成交、现金只够一笔：请求在前的先用现金（名义取等权额度与现金的小者），后一笔买不起 1 股 → 取消并说明原因。
     * 已有一笔持有中占掉 46 万：剩 4 万 < 等权额度 5 万。
     */
    @Test
    void 同日入场按请求先后分现金_不够的取消() {
        LocalDate entry = D.plusDays(1);
        SimTradeRow a = pending(1, entry, Instant.parse("2026-08-03T22:00:00Z"));
        SimTradeRow b = pending(2, entry, Instant.parse("2026-08-03T22:05:00Z"));
        when(sim.unfinished()).thenReturn(List.of(a, b));
        SimTradeRow held = withId(SimTradeRow.pending("sim-v1", 9, "F1", D.minusDays(30), 1, "STRONG_BUY", BigDecimal.TEN,
                Instant.parse("2026-07-01T22:00:00Z"), BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "ATR", null,
                D.minusDays(29)), 9, "HELD");
        SimTradeRow heldOpen = new SimTradeRow(9, "sim-v1", 9, "HELD", "F1", D.minusDays(30), 1, "STRONG_BUY", BigDecimal.TEN,
                held.requestedAt(), BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "ATR", null, D.minusDays(29),
                "OPEN", D.minusDays(29), BigDecimal.valueOf(46), 10_000, null, null, null, false, null, null, false, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, D.plusDays(5), null);
        when(sim.filled()).thenReturn(List.of(heldOpen));
        for (long id : new long[] {1, 2}) {
            InstrumentRow r = row(id, "S" + id);
            when(instruments.findById(id)).thenReturn(Optional.of(r));
            when(bars.find(eq(r.instrument()), eq(id), any(), any())).thenReturn(flat(r.instrument()));
        }
        when(rehabs.find(any(), anyLong())).thenReturn(List.of());
        when(instruments.find(any())).thenReturn(Optional.empty());
        when(sim.list(null)).thenReturn(List.of(a, b));
        when(sim.equity(any(), any())).thenReturn(List.of());
        when(days.between(eq(Market.US), any(), any())).thenAnswer(inv -> {
            LocalDate from = inv.getArgument(1);
            LocalDate to = inv.getArgument(2);
            List<LocalDate> out = new ArrayList<>();
            for (LocalDate x = from; !x.isAfter(to); x = x.plusDays(1)) {
                out.add(x);
            }
            return out;
        });

        service(Instant.parse("2026-08-05T00:00:00Z")).update(entry, ctx);

        ArgumentCaptor<SimTradeRow> c = ArgumentCaptor.forClass(SimTradeRow.class);
        verify(sim, atLeastOnce()).update(c.capture());
        SimTradeRow first = c.getAllValues().stream().filter(x -> x.id() == 1).reduce((x, y) -> y).orElseThrow();
        SimTradeRow second = c.getAllValues().stream().filter(x -> x.id() == 2).reduce((x, y) -> y).orElseThrow();
        assertThat(first.status()).isEqualTo("OPEN");
        assertThat(first.shares()).isEqualTo(400);           // 现金 4 万 ÷ 开盘 100
        assertThat(second.status()).isEqualTo("CANCELLED");
        assertThat(second.cancelReason()).contains("可用现金");
        verify(sim).event(eq(2L), eq("CANCELLED"), any());
    }
}
