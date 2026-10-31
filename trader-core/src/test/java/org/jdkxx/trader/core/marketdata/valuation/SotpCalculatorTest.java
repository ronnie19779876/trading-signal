package org.jdkxx.trader.core.marketdata.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jdkxx.trader.domain.valuation.SotpAssumptions;
import org.jdkxx.trader.domain.valuation.SotpBasis;
import org.jdkxx.trader.domain.valuation.SotpResult;
import org.jdkxx.trader.domain.valuation.SotpScenario;
import org.jdkxx.trader.domain.valuation.SotpSegment;
import org.junit.jupiter.api.Test;

/**
 * The golden case comes from the hand calculation of TSLA 2030 on 2026-09-19 (see the project
 * memory sotp-valuation-model): it was computed independently, not produced by this implementation,
 * so it can disprove the implementation rather than merely confirm itself.
 *
 * <p><b>Only the base scenario and the bull Robotaxi line have complete original assumptions</b>;
 * the bear $22 / bull $503 totals recorded at the time kept no per-line assumptions, so this test
 * does not try to reproduce those two numbers; reverse-engineering them would only make the
 * implementation's errors look consistent.
 */
class SotpCalculatorTest {

  private static final LocalDate AS_OF = LocalDate.of(2026, 9, 19);
  private static final double SHARES = 4_000_000_000d; // target-year 4 billion shares
  private static final double NET_CASH = 60_000_000_000d; // target-year net cash 60 billion
  private static final double PRICE = 364.18d; // 09-18 close

  /** Base scenario: volume, price, net margin and P/E all taken from that calculation. */
  private static SotpAssumptions tsla2030() {
    return new SotpAssumptions(
        AS_OF,
        2030,
        0.10d,
        SHARES,
        NET_CASH,
        List.of(
            segment(
                "卖车",
                "整车销售，含 FSD 买断收入",
                c(1_800_000, 38_000, 0.05, 12),
                c(2_200_000, 40_000, 0.07, 15),
                c(2_600_000, 42_000, 0.09, 18)),
            segment(
                "FSD 订阅",
                "只算私家车订阅，买断已计在卖车",
                c(900_000, 1_188, 0.55, 20),
                c(1_650_000, 1_188, 0.60, 30),
                c(2_400_000, 1_188, 0.65, 40)),
            segment(
                "Robotaxi",
                "只算打车收入，车辆与 FSD 不重复计",
                c(0, 50_000, 0.25, 30),
                c(300_000, 50_000, 0.25, 30),
                c(1_500_000, 60_000, 0.35, 40)),
            segment(
                "能源",
                "储能与光伏",
                c(100, 200_000_000d, 0.10, 15),
                c(150, 200_000_000d, 0.13, 22),
                c(220, 200_000_000d, 0.16, 30)),
            segment(
                "Semi 卡车",
                "重卡整车",
                c(10_000, 250_000, 0.05, 12),
                c(25_000, 250_000, 0.08, 15),
                c(60_000, 250_000, 0.10, 18))));
  }

  private static SotpBasis basis() {
    return new SotpBasis(PRICE, 35.0d, 6_100_000_000d / 1.6d, 0.0367d);
  }

  @Test
  void 基准情景逐业务线与股价复现09_19的手工测算() {
    SotpResult r = SotpCalculator.calculate(tsla2030(), basis());
    Map<String, Double> v = r.scenarios().get(SotpScenario.BASE).segmentValues();

    assertThat(v.get("卖车")).isCloseTo(92_400_000_000d, within(1d)); // 92.4 billion
    assertThat(v.get("FSD 订阅")).isCloseTo(35_283_600_000d, within(1d)); // 35.3 billion
    assertThat(v.get("Robotaxi")).isCloseTo(112_500_000_000d, within(1d)); // 112.5 billion
    assertThat(v.get("能源")).isCloseTo(85_800_000_000d, within(1d)); // 85.8 billion
    assertThat(v.get("Semi 卡车")).isCloseTo(7_500_000_000d, within(1d)); // 7.5 billion

    SotpResult.ScenarioValue base = r.scenarios().get(SotpScenario.BASE);
    assertThat(base.segmentTotal()).isCloseTo(333_483_600_000d, within(1d));
    assertThat(base.equityValue()).isCloseTo(393_483_600_000d, within(1d));
    assertThat(base.targetPrice()).isCloseTo(98.37d, within(0.01d)); // the $98 recorded at the time
    assertThat(base.presentValue())
        .isCloseTo(65.4d, within(0.2d)); // the present value of $65 recorded at the time
  }

  @Test
  void 牛市Robotaxi一条就是1_26万亿() {
    SotpResult r = SotpCalculator.calculate(tsla2030(), basis());
    assertThat(r.scenarios().get(SotpScenario.BULL).segmentValues().get("Robotaxi"))
        .isCloseTo(1_260_000_000_000d, within(1d));
  }

  @Test
  void 折现不可绕过_系数按要求回报率与折现期算() {
    SotpResult r = SotpCalculator.calculate(tsla2030(), basis());
    assertThat(r.horizonYears()).isCloseTo(4.28d, within(0.01d));
    assertThat(r.discountFactor()).isCloseTo(0.665d, within(0.002d));
    SotpResult.ScenarioValue base = r.scenarios().get(SotpScenario.BASE);
    assertThat(base.presentValue()).isLessThan(base.targetPrice());
    assertThat(base.presentValue())
        .isCloseTo(base.targetPrice() * r.discountFactor(), within(1e-9d));
  }

  @Test
  void 反推复现当时的结论_现价隐含2030约549美元_市值2_2万亿_所需净利约610亿() {
    SotpResult.Reverse rev = SotpCalculator.calculate(tsla2030(), basis()).reverse();
    assertThat(rev.requiredTargetPrice()).isCloseTo(548d, within(3d));
    assertThat(rev.requiredMarketCap()).isCloseTo(2.19e12d, within(0.02e12d));
    assertThat(rev.requiredNetIncome()).isCloseTo(61_000_000_000d, within(1_000_000_000d));
    assertThat(rev.requiredNetIncomeCagr()).isNotNull();
  }

  @Test
  void 缺本益比基准时不给反推结论_不硬编倍数() {
    SotpBasis noPe = new SotpBasis(PRICE, null, 3_800_000_000d, 0.0367d);
    assertThat(SotpCalculator.calculate(tsla2030(), noPe).reverse()).isNull();
  }

  @Test
  void 净利率口径核对_基准情景的隐含合并净利率远高于实际时标出偏离() {
    SotpResult.MarginCheck check = SotpCalculator.calculate(tsla2030(), basis()).marginCheck();
    assertThat(check.impliedNetMargin())
        .isCloseTo(0.1097d, within(0.0005d)); // Σ net income ÷ Σ revenue
    assertThat(check.actualNetMargin()).isEqualTo(0.0367d);
    assertThat(check.deviationPp()).isCloseTo(7.3d, within(0.1d));
    assertThat(check.ok()).isTrue(); // 7.3pp is within the 10pp threshold

    SotpBasis tinyMargin = new SotpBasis(PRICE, 35.0d, 3_800_000_000d, 0.005d);
    assertThat(SotpCalculator.calculate(tsla2030(), tinyMargin).marginCheck().ok()).isFalse();
  }

  @Test
  void 实际净利率取不到时不拦_但也不假装核对过() {
    SotpBasis noMargin = new SotpBasis(PRICE, 35.0d, 3_800_000_000d, null);
    SotpResult.MarginCheck check = SotpCalculator.calculate(tsla2030(), noMargin).marginCheck();
    assertThat(check.actualNetMargin()).isNull();
    assertThat(check.deviationPp()).isNull();
    assertThat(check.ok()).isTrue();
  }

  @Test
  void 单因子敏感度只动一条业务线_其余保持基准() {
    SotpResult r = SotpCalculator.calculate(tsla2030(), basis());
    SotpResult.Sensitivity robotaxi =
        r.sensitivities().stream()
            .filter(s -> s.segment().equals("Robotaxi"))
            .findFirst()
            .orElseThrow();

    // Hand calculation: the other four lines stay at base (92.4 + 35.3 + 85.8 + 7.5 billion =
    // 220,983,600,000), Robotaxi bear 0, bull 1.26 trillion
    double others = 92_400_000_000d + 35_283_600_000d + 85_800_000_000d + 7_500_000_000d;
    double low = (others + 0 + NET_CASH) / SHARES * r.discountFactor();
    double high = (others + 1_260_000_000_000d + NET_CASH) / SHARES * r.discountFactor();
    assertThat(robotaxi.lowPresentValue()).isCloseTo(low, within(1e-6d));
    assertThat(robotaxi.highPresentValue()).isCloseTo(high, within(1e-6d));
    assertThat(robotaxi.swing()).isCloseTo(high - low, within(1e-6d));

    // Largest swing first; Robotaxi's swing should far exceed every other line
    assertThat(r.sensitivities().get(0).segment()).isEqualTo("Robotaxi");
    assertThat(robotaxi.swing()).isGreaterThan(5 * r.sensitivities().get(1).swing());
  }

  @Test
  void 三个情景缺一不可_不接受单点估值() {
    Map<SotpScenario, SotpSegment.SegmentCase> onlyBase = new EnumMap<>(SotpScenario.class);
    onlyBase.put(SotpScenario.BASE, c(1, 1, 0.1, 10));
    assertThatThrownBy(() -> new SotpSegment("卖车", "整车", onlyBase))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BEAR");
  }

  @Test
  void 口径备注必填_挡住重复计算() {
    assertThatThrownBy(
            () -> segment("Robotaxi", "  ", c(1, 1, 0.1, 10), c(1, 1, 0.1, 10), c(1, 1, 0.1, 10)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("口径备注");
  }

  @Test
  void 净利率写成百分数会被挡下() {
    assertThatThrownBy(() -> c(1, 1, 7, 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("小数");
  }

  /**
   * {@code segmentValues}' javadoc explicitly says "in input order", but the implementation wrapped
   * it with {@code Map.copyOf}, dropping the LinkedHashMap's insertion order; the actual order was
   * decided by the SALT the JVM randomizes at every start (found in the 2026-09-25 full-project
   * review). All three scenarios must keep the order.
   */
  @Test
  void 逐业务线的金额按输入顺序() {
    SotpResult r = SotpCalculator.calculate(tsla2030(), basis());
    List<String> input = List.of("卖车", "FSD 订阅", "Robotaxi", "能源", "Semi 卡车");
    for (SotpScenario sc : SotpScenario.values()) {
      assertThat(r.scenarios().get(sc).segmentValues().keySet())
          .as("%s 情景的逐业务线金额要按输入顺序", sc)
          .containsExactlyElementsOf(input);
    }
  }

  private static SotpSegment segment(
      String name,
      String scope,
      SotpSegment.SegmentCase bear,
      SotpSegment.SegmentCase base,
      SotpSegment.SegmentCase bull) {
    Map<SotpScenario, SotpSegment.SegmentCase> cases = new EnumMap<>(SotpScenario.class);
    cases.put(SotpScenario.BEAR, bear);
    cases.put(SotpScenario.BASE, base);
    cases.put(SotpScenario.BULL, bull);
    return new SotpSegment(name, scope, cases);
  }

  private static SotpSegment.SegmentCase c(double volume, double price, double margin, double pe) {
    return new SotpSegment.SegmentCase(volume, price, margin, pe);
  }

  private static org.assertj.core.data.Offset<Double> within(double v) {
    return org.assertj.core.data.Offset.offset(v);
  }
}
