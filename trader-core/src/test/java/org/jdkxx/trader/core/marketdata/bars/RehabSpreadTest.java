package org.jdkxx.trader.core.marketdata.bars;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全量复权因子的每日限量（{@link DeepBackfillService#dueToday}）。
 *
 * <p>背景：全量 521 只若在同一天刷过，7 天后会在同一次增量里一起到期，多花约 300 秒，
 * 2026-09-10 因此让增量跑了 691 秒、挤掉了估值作业的时点。
 */
class RehabSpreadTest {

    private static List<Long> ids(long fromInclusive, long toInclusive) {
        return LongStream.rangeClosed(fromInclusive, toInclusive).boxed().toList();
    }

    @Test
    void 集中到期时每次只刷五分之一_最久未刷的优先() {
        List<Long> due = DeepBackfillService.dueToday(ids(1, 521), Set.of(), 521, 5);

        assertThat(due).hasSize(105).containsExactlyElementsOf(ids(1, 105));
    }

    @Test
    void 已在池或持仓里的不占名额() {
        List<Long> due = DeepBackfillService.dueToday(List.of(1L, 2L, 3L, 4L), Set.of(1L), 10, 5);

        assertThat(due).containsExactly(2L, 3L);
    }

    @Test
    void 到期的少于上限时全刷() {
        assertThat(DeepBackfillService.dueToday(List.of(7L, 8L), Set.of(), 521, 5)).containsExactly(7L, 8L);
    }

    @Test
    void 摊开天数不大于1时不限量_等同旧行为() {
        assertThat(DeepBackfillService.dueToday(ids(1, 521), Set.of(), 521, 1)).hasSize(521);
        assertThat(DeepBackfillService.dueToday(ids(1, 521), Set.of(), 521, 0)).hasSize(521);
    }

    @Test
    void 全量很小时每次至少刷一只() {
        assertThat(DeepBackfillService.dueToday(List.of(1L, 2L), Set.of(), 2, 5)).containsExactly(1L);
    }

    /**
     * 模拟生产：521 只同一天（周一）全刷过，此后每个工作日跑一次增量，到期阈值 7 天。
     * 旧行为是每 7 天一次性刷 521 只；限量后要满足：
     * 每次都不超过 105 只，而且任何一只从上次刷新到再次刷新都不超过 14 天。
     */
    @Test
    void 模拟八周_集中到期会自己摊开且不会饿死() {
        int universe = 521;
        LocalDate start = LocalDate.of(2026, 9, 14);   // 周一
        Map<Long, LocalDate> lastFetched = new HashMap<>();
        ids(1, universe).forEach(id -> lastFetched.put(id, start));

        int maxPerRun = 0;
        long maxGapDays = 0;
        List<Integer> lastWeek = new ArrayList<>();
        for (int d = 1; d <= 56; d++) {
            LocalDate day = start.plusDays(d);
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            LocalDate threshold = day.minusDays(7);
            List<Long> staleOldestFirst = lastFetched.entrySet().stream()
                    .filter(e -> e.getValue().isBefore(threshold))
                    .sorted(Map.Entry.<Long, LocalDate>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.naturalOrder())))
                    .map(Map.Entry::getKey)
                    .toList();
            List<Long> due = DeepBackfillService.dueToday(staleOldestFirst, Set.of(), universe, 5);
            for (Long id : due) {
                maxGapDays = Math.max(maxGapDays, java.time.temporal.ChronoUnit.DAYS.between(lastFetched.get(id), day));
                lastFetched.put(id, day);
            }
            maxPerRun = Math.max(maxPerRun, due.size());
            if (d > 49) {
                lastWeek.add(due.size());
            }
        }

        assertThat(maxPerRun).as("每次增量刷新的全量因子只数").isLessThanOrEqualTo(105);
        assertThat(maxGapDays).as("同一只两次刷新的最长间隔（天）").isLessThanOrEqualTo(14);
        assertThat(lastWeek).as("第八周每天都在刷，而不是攒到某一天").allMatch(n -> n > 0 && n <= 105);
    }
}
