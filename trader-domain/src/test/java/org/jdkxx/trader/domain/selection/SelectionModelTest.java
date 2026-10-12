package org.jdkxx.trader.domain.selection;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionModelTest {

    private static final Set<Metric> ALL = EnumSet.allOf(Metric.class);

    private static StockInputs stock(long id, String sector, double base) {
        Map<Metric, Double> m = new EnumMap<>(Metric.class);
        for (Metric x : Metric.values()) {
            m.put(x, base);
        }
        return new StockInputs(id, "S" + id, sector, null, null, m, ALL);
    }

    private static List<StockInputs> sector(String name, int n, long firstId) {
        List<StockInputs> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(stock(firstId + i, name, i));
        }
        return out;
    }

    @Test
    void 板块内百分位_最好100最差0_强制分布十二十四十二十十() {
        List<StockScore> r = SelectionModel.score(sector("IT", 10, 1));

        StockScore best = r.stream().filter(s -> s.instrumentId() == 10).findFirst().orElseThrow();
        StockScore worst = r.stream().filter(s -> s.instrumentId() == 1).findFirst().orElseThrow();
        assertThat(best.metricPct().get(Metric.ROE)).isEqualTo(100.0);
        assertThat(worst.metricPct().get(Metric.ROE)).isEqualTo(0.0);
        assertThat(best.rating()).isEqualTo(Rating.STRONG_BUY);
        assertThat(worst.rating()).isEqualTo(Rating.STRONG_SELL);
        assertThat(r.stream().filter(s -> s.rating() == Rating.BUY)).hasSize(2);
        assertThat(r.stream().filter(s -> s.rating() == Rating.HOLD)).hasSize(4);
        assertThat(r.stream().filter(s -> s.rating() == Rating.SELL)).hasSize(2);
        assertThat(best.compositePct()).isEqualTo(100.0);
    }

    @Test
    void 并列取平均名次() {
        List<StockInputs> in = sector("IT", 8, 1);
        in.set(0, stock(1, "IT", 5));   // 与 id=6 的 5 并列
        List<StockScore> r = SelectionModel.score(in);

        double p1 = r.get(0).metricPct().get(Metric.ROE);
        double p6 = r.get(5).metricPct().get(Metric.ROE);
        assertThat(p1).isEqualTo(p6);
    }

    /** 负的盈利收益率（亏损）是真实值，自然排在最后，不当缺失。 */
    @Test
    void 亏损的盈利收益率排最后() {
        List<StockInputs> in = sector("IT", 8, 1);
        Map<Metric, Double> m = new EnumMap<>(in.get(7).metrics());
        m.put(Metric.EARNINGS_YIELD, -0.02);
        in.set(7, new StockInputs(8, "S8", "IT", null, null, m, ALL));
        List<StockScore> r = SelectionModel.score(in);

        assertThat(r.get(7).metricPct().get(Metric.EARNINGS_YIELD)).isEqualTo(0.0);
    }

    @Test
    void 板块不足八只整组不评() {
        List<StockInputs> in = new ArrayList<>(sector("IT", 8, 1));
        in.addAll(sector("Real Estate", 5, 100));
        List<StockScore> r = SelectionModel.score(in);

        assertThat(r.stream().filter(s -> "Real Estate".equals(s.sector())))
                .allMatch(s -> s.exclusion() == Exclusion.SECTOR_TOO_SMALL && !s.rated());
        assertThat(r.stream().filter(s -> "IT".equals(s.sector()))).allMatch(StockScore::rated);
    }

    /** 被排除的标的不占板块名额、不参与排名，但照样输出（审计要看）。 */
    @Test
    void 排除的不参与排名也不算板块人数() {
        List<StockInputs> in = new ArrayList<>(sector("IT", 8, 1));
        in.add(new StockInputs(99, "ADR", "IT", Exclusion.SECURITY_TYPE, "ADR", Map.of(), ALL));
        List<StockScore> r = SelectionModel.score(in);

        assertThat(r).hasSize(9);
        assertThat(r.get(8).exclusion()).isEqualTo(Exclusion.SECURITY_TYPE);
        assertThat(r.stream().filter(StockScore::rated)).hasSize(8);
    }

    /** 银行模板没有毛利率与自由现金流利润率：盈利能力只看 ROE，不因为缺两项就不评级。 */
    @Test
    void 金融模板只按适用指标算因子() {
        List<StockInputs> in = new ArrayList<>();
        Set<Metric> bank = EnumSet.allOf(Metric.class);
        bank.remove(Metric.GROSS_MARGIN);
        bank.remove(Metric.FCF_MARGIN);
        for (int i = 0; i < 8; i++) {
            Map<Metric, Double> m = new EnumMap<>(Metric.class);
            for (Metric x : bank) {
                m.put(x, (double) i);
            }
            in.add(new StockInputs(i + 1, "B" + i, "Financials", null, null, m, bank));
        }
        List<StockScore> r = SelectionModel.score(in);

        assertThat(r).allMatch(StockScore::rated);
        assertThat(r.get(7).factorScores().get(Factor.PROFITABILITY)).isEqualTo(100.0);
    }

    @Test
    void 指标不到一半有值时因子缺_不评级() {
        List<StockInputs> in = sector("IT", 8, 1);
        Map<Metric, Double> m = new EnumMap<>(in.get(0).metrics());
        m.remove(Metric.MOMENTUM_3M);
        m.remove(Metric.MOMENTUM_6M);
        in.set(0, new StockInputs(1, "S1", "IT", null, null, m, ALL));
        List<StockScore> r = SelectionModel.score(in);

        assertThat(r.get(0).factorScores()).doesNotContainKey(Factor.MOMENTUM);
        assertThat(r.get(0).rated()).isFalse();
        assertThat(r.stream().filter(StockScore::rated)).hasSize(7);
    }

    @Test
    void 同一份输入结果完全相同() {
        List<StockInputs> in = sector("IT", 12, 1);
        assertThat(SelectionModel.score(in)).isEqualTo(SelectionModel.score(in));
    }
}
