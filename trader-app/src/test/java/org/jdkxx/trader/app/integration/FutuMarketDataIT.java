package org.jdkxx.trader.app.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jdkxx.trader.core.marketdata.bars.BarAdjuster;
import org.jdkxx.trader.core.marketdata.bars.FactorMode;
import org.jdkxx.trader.domain.Adjustment;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.HistoryQuota;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.TradingDay;
import org.jdkxx.trader.gateway.futu.FutuGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Read-only integration test against a real OpenD (AAPL already holds a history-quota slot this
 * week, so nothing more is consumed; it briefly subscribes to AAPL daily bars, released when the
 * connection closes). The last part uses Futu's own forward-adjusted series to decide the semantics
 * of the price-adjustment factors (cumulative / per event); the conclusion went into FactorMode and
 * the configuration default.
 */
@EnabledIfSystemProperty(named = "trader.integration", matches = "true")
class FutuMarketDataIT {

  private static final Instrument AAPL = Instrument.us("AAPL");

  @Test
  void 静态信息额度交易日复权订阅与历史分页() throws Exception {
    String host = IntegrationEnv.env("TRADER_FUTU_HOST");
    int port = Integer.parseInt(IntegrationEnv.env("TRADER_FUTU_PORT"));
    try (FutuGateway g = new FutuGateway(IntegrationEnv.futu(host, port, Duration.ofSeconds(2)))) {
      g.connect().get(20, TimeUnit.SECONDS);

      List<InstrumentStatic> statics =
          g.staticInfo(List.of(AAPL, Instrument.us("SPY"), Instrument.us("ZZZZNOSUCH")))
              .get(15, TimeUnit.SECONDS);
      System.out.println("[IT] statics=" + statics);
      assertThat(statics).hasSizeGreaterThanOrEqualTo(2);
      assertThat(statics)
          .anyMatch(s -> s.instrument().equals(AAPL) && s.lotSize() == 1 && s.name() != null);

      HistoryQuota q = g.historyQuota().get(15, TimeUnit.SECONDS);
      System.out.println(
          "[IT] quota used=" + q.used() + " remain=" + q.remain() + " items=" + q.items().size());
      assertThat(q.total()).isGreaterThan(0);

      LocalDate today = LocalDate.now();
      List<TradingDay> days =
          g.tradingDays(Market.US, today.minusDays(45), today).get(15, TimeUnit.SECONDS);
      System.out.println("[IT] tradingDays=" + days.size() + " last=" + days.get(days.size() - 1));
      assertThat(days.size()).isBetween(25, 35);

      List<RehabFactor> factors = g.rehab(AAPL).get(15, TimeUnit.SECONDS);
      System.out.println(
          "[IT] rehab entries=" + factors.size() + " last=" + factors.get(factors.size() - 1));
      assertThat(factors.size()).isGreaterThan(50);

      g.subscribeDailyBars(List.of(AAPL)).get(15, TimeUnit.SECONDS);
      Thread.sleep(1500);
      List<DailyBar> recent = g.recentDailyBars(AAPL, 30).get(15, TimeUnit.SECONDS);
      System.out.println(
          "[IT] recent bars="
              + recent.size()
              + " first="
              + recent.get(0).tradeDate()
              + " last="
              + recent.get(recent.size() - 1).tradeDate());
      assertThat(recent.size()).isBetween(25, 30);

      // History paging: fetch 2026-01-01 to now (<= 1000 bars per page; only verifies the API and
      // fields) and check it matches the same day's close from the subscription channel
      List<DailyBar> hist =
          g.historyDailyBars(AAPL, LocalDate.of(2026, 1, 1), today).get(60, TimeUnit.SECONDS);
      System.out.println("[IT] history bars=" + hist.size() + " first=" + hist.get(0).tradeDate());
      Map<LocalDate, DailyBar> byDate =
          hist.stream().collect(Collectors.toMap(DailyBar::tradeDate, b -> b));
      for (DailyBar r : recent) {
        DailyBar h = byDate.get(r.tradeDate());
        if (h != null) {
          assertThat(h.close()).as("%s 两条通道收盘价一致", r.tradeDate()).isEqualByComparingTo(r.close());
        }
      }

      // Adjustment semantics: take a range spanning two or more ex-dividend dates and compare with
      // Futu's forward-adjusted series
      LocalDate from = LocalDate.of(2026, 4, 20);
      LocalDate to = LocalDate.of(2026, 5, 20);
      List<DailyBar> none = g.historyDailyBars(AAPL, from, to).get(60, TimeUnit.SECONDS);
      List<DailyBar> futuFwd =
          g.historyDailyBarsAdjusted(AAPL, from, to, 1).get(60, TimeUnit.SECONDS);
      Map<LocalDate, BigDecimal> fwd =
          futuFwd.stream().collect(Collectors.toMap(DailyBar::tradeDate, DailyBar::close));
      List<DailyBar> futuBwd =
          g.historyDailyBarsAdjusted(AAPL, from, to, 2).get(60, TimeUnit.SECONDS);
      Map<LocalDate, BigDecimal> bwd =
          futuBwd.stream().collect(Collectors.toMap(DailyBar::tradeDate, DailyBar::close));
      for (FactorMode mode : FactorMode.values()) {
        List<DailyBar> oursBwd = BarAdjuster.adjust(none, factors, Adjustment.BACKWARD, mode);
        BigDecimal maxRelBwd = BigDecimal.ZERO;
        for (DailyBar b : oursBwd) {
          BigDecimal ref = bwd.get(b.tradeDate());
          if (ref != null) {
            maxRelBwd =
                maxRelBwd.max(b.close().subtract(ref).abs().divide(ref, 8, RoundingMode.HALF_UP));
          }
        }
        System.out.println(
            "[IT] backward factor mode "
                + mode
                + " 最大相对误差="
                + maxRelBwd.toPlainString()
                + "（样本 "
                + oursBwd.get(0).tradeDate()
                + " 我们="
                + oursBwd.get(0).close()
                + " 富途="
                + bwd.get(oursBwd.get(0).tradeDate())
                + "）");
      }
      for (FactorMode mode : FactorMode.values()) {
        List<DailyBar> ours = BarAdjuster.adjust(none, factors, Adjustment.FORWARD, mode);
        BigDecimal maxRel = BigDecimal.ZERO;
        for (DailyBar b : ours) {
          BigDecimal ref = fwd.get(b.tradeDate());
          if (ref == null) {
            continue;
          }
          BigDecimal rel = b.close().subtract(ref).abs().divide(ref, 8, RoundingMode.HALF_UP);
          if (rel.compareTo(maxRel) > 0) {
            maxRel = rel;
          }
        }
        System.out.println(
            "[IT] factor mode "
                + mode
                + " 最大相对误差="
                + maxRel.toPlainString()
                + "（样本 "
                + ours.get(0).tradeDate()
                + " 我们="
                + ours.get(0).close()
                + " 富途="
                + fwd.get(ours.get(0).tradeDate())
                + "）");
      }
      g.disconnect();
    }
  }
}
