package org.jdkxx.trader.core.marketdata.universe;

import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.TestProperties;
import org.jdkxx.trader.domain.IndexCode;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.ConstituentRow;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 成分股同步的「单次退出数」守护。
 *
 * <p>为什么需要它（2026-09-25 全项目审查发现）：{@code apply} 是纯集合差，来源少给多少就退出多少；
 * 而 {@code WikipediaUniverseSource} 只在解析少于 50 行时才报错，标普 500 有 503 只——
 * 页面结构变化让它只解析出 60 行仍然「可信」，一次同步就把 440 多只标记为退出。
 * 更糟的是看不见：日线审计的分母与成分股同源，掉了分母跟着掉，完整性检查照样全绿。
 *
 * <p>阈值依据（生产库实录）：SP500 现有 503 只、历史退出 0 次；NDX100 现有 101 只、历史退出 1 次。
 */
class UniverseSyncGuardTest {

    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final IndexConstituentRepository constituents = mock(IndexConstituentRepository.class);
    private final MarketDataProperties props = TestProperties.defaults();

    private UniverseSyncService service() {
        when(instruments.upsert(any(Instrument.class), any()))   // 用 any() 不用 anyString()：CSV 行的 name 是 null，anyString() 不匹配 null，
                // 三只会一起落到 mock 默认返回 0、collapse 成一只（本测试第一版就栽在这里）
                .thenAnswer(inv -> (long) ((Instrument) inv.getArgument(0)).symbol().hashCode());
        return new UniverseSyncService(props, mock(UniverseSource.class), mock(SpyHoldingsCrossCheck.class),
                instruments, constituents, mock(MarketDataGateway.class));
    }

    /** 现有成员：symbol 的 hashCode 当 id，与 service() 里的 upsert 替身一致。 */
    private void existing(IndexCode index, List<String> symbols) {
        List<ConstituentRow> rows = new ArrayList<>();
        for (String s : symbols) {
            rows.add(new ConstituentRow(index, s.hashCode(), "IT", "Sub", "GICS", LocalDate.of(2026, 9, 3), null, "WIKI"));
        }
        when(constituents.current(index)).thenReturn(rows);
    }

    private static List<ConstituentEntry> entries(IndexCode index, List<String> symbols) {
        return symbols.stream().map(s -> new ConstituentEntry(index, s, s, "IT", "Sub", null)).toList();
    }

    private static List<String> symbols(String prefix, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(prefix + i);
        }
        return out;
    }

    @Test
    void 阈值取绝对值与百分比的较大者() {
        UniverseSyncService s = service();
        assertThat(s.removalLimit(503)).isEqualTo(25);      // 503 × 5% = 25 > 5
        assertThat(s.removalLimit(101)).isEqualTo(5);       // 101 × 5% = 5，与绝对值持平
        assertThat(s.removalLimit(20)).isEqualTo(5);        // 20 × 5% = 1 < 5，取绝对值
    }

    /** 要害：来源只给了 60 行时，443 只不能被悄悄标记退出。 */
    @Test
    void 来源大幅缩水时整个指数跳过_一条都不改() {
        UniverseSyncService s = service();
        existing(IndexCode.SP500, symbols("S", 503));

        UniverseSyncService.IndexResult r = s.apply(IndexCode.SP500, entries(IndexCode.SP500, symbols("S", 60)), "WIKI", false);

        assertThat(r.error()).isNotNull().contains("退出 443").contains("阈值 25").contains("force");
        assertThat(r.added()).isZero();
        assertThat(r.removed()).isZero();
        verify(constituents, never()).close(any(), anyLong(), any());
        verify(constituents, never()).add(any(), anyLong(), any(), any(), any(), any());
    }

    /** 被挡下时不做部分应用：新增也一起跳过，避免留下半新半旧的状态。 */
    @Test
    void 被挡下时新增也不应用() {
        UniverseSyncService s = service();
        existing(IndexCode.SP500, symbols("S", 503));
        List<String> fetched = new ArrayList<>(symbols("S", 60));
        fetched.add("NEWCO");                                   // 来源里有一只新标的

        UniverseSyncService.IndexResult r = s.apply(IndexCode.SP500, entries(IndexCode.SP500, fetched), "WIKI", false);

        assertThat(r.error()).isNotNull();
        verify(constituents, never()).add(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void force_放行大批退出() {
        UniverseSyncService s = service();
        existing(IndexCode.SP500, symbols("S", 503));

        UniverseSyncService.IndexResult r = s.apply(IndexCode.SP500, entries(IndexCode.SP500, symbols("S", 60)), "WIKI", true);

        assertThat(r.error()).isNull();
        assertThat(r.removed()).isEqualTo(443);
        verify(constituents, times(443)).close(eq(IndexCode.SP500), anyLong(), any());
    }

    /** 正常的季度调整不受影响：真实单次退出是 0~1 只。 */
    @Test
    void 小幅变动照常应用() {
        UniverseSyncService s = service();
        existing(IndexCode.SP500, symbols("S", 503));
        List<String> fetched = new ArrayList<>(symbols("S", 503));
        fetched.remove(0);
        fetched.remove(0);
        fetched.add("NEWA");

        UniverseSyncService.IndexResult r = s.apply(IndexCode.SP500, entries(IndexCode.SP500, fetched), "WIKI", false);

        assertThat(r.error()).isNull();
        assertThat(r.removed()).isEqualTo(2);
        assertThat(r.added()).isEqualTo(1);
    }

    /** 首次导入时库里是空的，退出数为 0，不能被守护挡住。 */
    @Test
    void 首次导入不受限() {
        UniverseSyncService s = service();
        existing(IndexCode.SP500, List.of());

        UniverseSyncService.IndexResult r = s.apply(IndexCode.SP500, entries(IndexCode.SP500, symbols("S", 503)), "WIKI", false);

        assertThat(r.error()).isNull();
        assertThat(r.added()).isEqualTo(503);
    }

    /** CSV 兜底导入走同一套 apply，同样受守护——三行 CSV 不能关掉整个指数。 */
    @Test
    void CSV导入同样受守护() {
        UniverseSyncService s = service();
        existing(IndexCode.SP500, symbols("S", 503));

        List<UniverseSyncService.IndexResult> out = s.importCsv("SP500,S0\nSP500,S1\nSP500,S2\n", false);

        assertThat(out).singleElement().satisfies(r -> assertThat(r.error()).isNotNull().contains("退出 500"));
        verify(constituents, never()).close(any(), anyLong(), any());
    }
}
