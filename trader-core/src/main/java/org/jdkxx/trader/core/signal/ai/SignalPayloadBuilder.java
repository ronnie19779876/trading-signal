package org.jdkxx.trader.core.signal.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jdkxx.trader.domain.CompanyProfile;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.ValuationSnapshot;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.QuarterAvailability;
import org.jdkxx.trader.domain.signal.GateResult;
import org.jdkxx.trader.domain.signal.Indicators;
import org.jdkxx.trader.domain.signal.SentinelEvaluation;
import org.jdkxx.trader.domain.signal.SentinelThresholds;
import org.jdkxx.trader.domain.signal.SignalBar;
import org.jdkxx.trader.domain.signal.SignalInputs;
import org.jdkxx.trader.storage.marketdata.CompanyProfileRepository;
import org.jdkxx.trader.storage.marketdata.ConstituentRow;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.marketdata.ValuationRepository;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;

/**
 * The input sent to the model: everything is precomputed in code, LinkedHashMap keeps the key order
 * fixed (the prefix cache must hit and the input hash must be stable), and numbers are always given
 * with a finite number of decimals.
 *
 * <p>Deliberately left out: position size, cost, net liquidation, account information, and whether
 * the name is held at all (user decision 2026-09-17). Only financial periods ending before the
 * judgement date are included; when the judgement date is more than 7 days before today (manual
 * analysis of a past day) the period must also have been announced by the judgement date - the
 * announcement date uses the same rule as quant selection ({@link QuarterAvailability}: nearest
 * broker announcement date within 75 days after period end, falling back to the statutory deadline
 * when none matches). Before 3.4.8 announcement dates were not wired in and everything was simply
 * held back 45 days. {@code calendarHint}: see {@link EarningsCalendarHint}.
 */
public class SignalPayloadBuilder {

  /**
   * Financials whitelist: (statement, field id) to output key. A field id means different things in
   * different statement types, so it must be looked up per statement (checked against MSFT in the
   * dev database, 2026-09-17).
   */
  static final Map<FinancialStatement, Map<Long, String>> FIELDS =
      new java.util.EnumMap<>(FinancialStatement.class);

  static {
    // Key order determines the field order of the input JSON and must be fixed (prefix cache, input
    // hash), hence EnumMap + LinkedHashMap rather than Map.of
    FIELDS.put(
        FinancialStatement.INCOME,
        linked(
            8001L,
            "revenue",
            8004L,
            "grossProfit",
            8017L,
            "operatingIncome",
            8043L,
            "netIncomeToParent",
            8048L,
            "dilutedEps"));
    FIELDS.put(
        FinancialStatement.CASH_FLOW,
        linked(8015L, "operatingCashFlow", 8046L, "capitalExpenditure", 8072L, "freeCashFlow"));
    FIELDS.put(
        FinancialStatement.MAIN_INDEX,
        linked(
            14002L,
            "grossMargin",
            14003L,
            "operatingMargin",
            14005L,
            "netMarginToParent",
            14019L,
            "interestBearingDebtRatio",
            14020L,
            "currentRatio",
            14029L,
            "roe",
            14031L,
            "roic",
            14032L,
            "fcfToRevenue",
            14039L,
            "revenueCagr3y",
            14041L,
            "netIncomeCagr3y"));
  }

  /** Amount fields (output in millions of USD, key suffixed with UsdM) */
  static final Set<String> AMOUNTS =
      Set.of(
          "revenue",
          "grossProfit",
          "operatingIncome",
          "netIncomeToParent",
          "operatingCashFlow",
          "capitalExpenditure",
          "freeCashFlow");

  /** Fields that also get a year-over-year change */
  static final Set<String> WITH_YOY =
      Set.of("revenue", "operatingIncome", "netIncomeToParent", "dilutedEps", "freeCashFlow");

  private final InstrumentRepository instruments;
  private final IndexConstituentRepository constituents;
  private final DailyBarRepository bars;
  private final RehabFactorRepository rehabs;
  private final TradingDayRepository days;
  private final ValuationRepository valuations;
  private final FinancialRepository financials;
  private final CompanyProfileRepository profiles;
  private final SelectionEarningsRepository earnings;
  private final ZoneId zone;
  private final SentinelThresholds th = SentinelThresholds.V1;

  public SignalPayloadBuilder(
      InstrumentRepository instruments,
      IndexConstituentRepository constituents,
      DailyBarRepository bars,
      RehabFactorRepository rehabs,
      TradingDayRepository days,
      ValuationRepository valuations,
      FinancialRepository financials,
      CompanyProfileRepository profiles,
      SelectionEarningsRepository earnings,
      ZoneId zone) {
    this.instruments = instruments;
    this.constituents = constituents;
    this.bars = bars;
    this.rehabs = rehabs;
    this.days = days;
    this.valuations = valuations;
    this.financials = financials;
    this.profiles = profiles;
    this.earnings = earnings;
    this.zone = zone;
  }

  public Map<String, Object> build(InstrumentRow row, SentinelEvaluation e, LocalDate today) {
    LocalDate asOf = e.asOf();
    List<String> caveats = new ArrayList<>();
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("meta", meta(row, asOf));
    root.put("signal", signal(e));
    root.put("technicals", technicals(row, asOf, caveats));
    root.put("valuation", valuation(row, asOf, caveats));
    boolean pastDate = asOf.isBefore(today.minusDays(7));
    List<EarningsEvent> events = earnings.events(row.id());
    TreeSet<LocalDate> calendar =
        new TreeSet<>(days.between(Market.US, asOf.minusYears(4), asOf.plusDays(400)));
    Map<String, Object> fin = financials(row, asOf, pastDate, events, calendar, caveats);
    root.put("financials", fin);
    root.put(
        "calendarHint",
        EarningsCalendarHint.build(
            events, asOf, latestQuarterEnd(fin), calendar, pastDate, caveats));
    root.put("profile", profile(row, caveats));
    root.put("caveats", caveats);
    return root;
  }

  private Map<String, Object> meta(InstrumentRow row, LocalDate asOf) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("symbol", row.symbol());
    m.put("name", row.name());
    m.put("nameCn", row.nameCn());
    List<ConstituentRow> idx =
        constituents.historyOf(row.id()).stream().filter(c -> c.until() == null).toList();
    m.put("indexes", idx.stream().map(c -> c.index().name()).distinct().toList());
    m.put(
        "sector",
        idx.stream().map(ConstituentRow::sector).filter(s -> s != null).findFirst().orElse(null));
    m.put(
        "subIndustry",
        idx.stream()
            .map(ConstituentRow::subIndustry)
            .filter(s -> s != null)
            .findFirst()
            .orElse(null));
    m.put("asOf", asOf.toString());
    m.put("ruleset", th.version());
    return m;
  }

  private Map<String, Object> signal(SentinelEvaluation e) {
    Map<String, Object> m = new LinkedHashMap<>();
    List<Map<String, Object>> gates = new ArrayList<>();
    for (GateResult g : e.gates()) {
      Map<String, Object> x = new LinkedHashMap<>();
      x.put("gate", g.gate().name());
      x.put("verdict", g.verdict().name());
      x.put("criteria", g.criteria());
      gates.add(x);
    }
    m.put("gates", gates);
    if (e.hitZone() != null) {
      Map<String, Object> zoneMap = new LinkedHashMap<>();
      zoneMap.put("bottom", r2(e.hitZone().bottom()));
      zoneMap.put("top", r2(e.hitZone().top()));
      zoneMap.put("touches", e.hitZone().touches());
      m.put("supportZone", zoneMap);
    }
    GateResult risk =
        e.gates().stream().filter(g -> g.gate() == GateResult.Gate.RISK).findFirst().orElseThrow();
    m.put("close", r2((Double) e.indicators().get("close")));
    m.put("stop", r2((Double) risk.values().get("stop")));
    m.put("stopDistancePct", r2((Double) risk.values().get("stopDistance") * 100));
    if (e.exitPlan() != null) {
      m.put("plusOneR", r2(e.exitPlan().plusOneR()));
      m.put("chandelierStop", r2(e.exitPlan().chandelierStop()));
      m.put("resistanceTarget", e.exitPlan().target() == null ? null : r2(e.exitPlan().target()));
      m.put("rewardRisk", e.exitPlan().rewardRisk() == null ? null : r2(e.exitPlan().rewardRisk()));
    }
    m.put("fibonacciConfluence", e.bonus().get("fibConfluence"));
    m.put("macdHistogramPositive", e.bonus().get("macdPositive"));
    return m;
  }

  /**
   * Technicals on the structural price basis as of the judgement date. Needs at least 260 bars
   * (250-day return plus the 52-week high / low window).
   */
  private Map<String, Object> technicals(InstrumentRow row, LocalDate asOf, List<String> caveats) {
    List<SignalBar> series = structural(row, asOf);
    Map<String, Object> m = new LinkedHashMap<>();
    if (series.size() < 260) {
      caveats.add("技术面样本不足 260 根");
      return m;
    }
    int t = series.size() - 1;
    double[] close = series.stream().mapToDouble(SignalBar::close).toArray();
    double[] high = series.stream().mapToDouble(SignalBar::high).toArray();
    double[] low = series.stream().mapToDouble(SignalBar::low).toArray();
    double[] volume = series.stream().mapToDouble(SignalBar::volume).toArray();
    double sma50 = Indicators.sma(close, 50)[t];
    double sma200 = Indicators.sma(close, 200)[t];
    double atr = Indicators.wilderAtr(high, low, close, 14)[t];
    m.put("close", r2(close[t]));
    m.put("pctAboveSma50", r2((close[t] / sma50 - 1) * 100));
    m.put("pctAboveSma200", r2((close[t] / sma200 - 1) * 100));
    m.put("atrPctOfClose", r2(atr / close[t] * 100));
    m.put("relativeVolume", r2(Indicators.relativeVolume(volume, t, 20)));
    Map<String, Object> returns = new LinkedHashMap<>();
    Map<String, Object> excess = new LinkedHashMap<>();
    // SPY raw closes: excess return is a ratio, and SPY has had no split in the window
    Map<LocalDate, Double> spy = spyCloses(asOf);
    for (int n : new int[] {20, 60, 250}) {
      double own = close[t] / close[t - n] - 1;
      returns.put("d" + n, r2(own * 100));
      Double s0 = spy.get(series.get(t - n).date());
      Double s1 = spy.get(series.get(t).date());
      excess.put("d" + n, s0 == null || s1 == null ? null : r2((own - (s1 / s0 - 1)) * 100));
    }
    m.put("returnPct", returns);
    m.put("excessVsSpyPct", excess);
    double hi = Double.NEGATIVE_INFINITY;
    double lo = Double.POSITIVE_INFINITY;
    for (int i = t - 251; i <= t; i++) {
      hi = Math.max(hi, high[i]);
      lo = Math.min(lo, low[i]);
    }
    m.put("pctBelow52wHigh", r2((1 - close[t] / hi) * 100));
    m.put("pctAbove52wLow", r2((close[t] / lo - 1) * 100));
    List<Map<String, Object>> last = new ArrayList<>();
    for (int i = t - 14; i <= t; i++) {
      SignalBar b = series.get(i);
      Map<String, Object> x = new LinkedHashMap<>();
      x.put("date", b.date().toString());
      x.put("open", r2(b.open()));
      x.put("high", r2(b.high()));
      x.put("low", r2(b.low()));
      x.put("close", r2(b.close()));
      x.put("volume", Math.round(b.volume()));
      last.add(x);
    }
    m.put("last15Bars", last);
    return m;
  }

  private List<SignalBar> structural(InstrumentRow row, LocalDate asOf) {
    LocalDate from = asOf.minusDays(th.windowCalendarDays());
    List<DailyBar> raw = bars.find(row.instrument(), row.id(), from, asOf);
    List<RehabFactor> factors = rehabs.find(row.instrument(), row.id());
    Set<LocalDate> calendar = new HashSet<>(days.between(Market.US, from, asOf));
    SignalInputs.Prepared p = SignalInputs.prepare(raw, factors, calendar, asOf, th);
    return p.bars();
  }

  private Map<LocalDate, Double> spyCloses(LocalDate asOf) {
    Map<LocalDate, Double> out = new LinkedHashMap<>();
    instruments
        .find(Instrument.us("SPY"))
        .ifPresent(
            spy ->
                bars.find(spy.instrument(), spy.id(), asOf.minusDays(400), asOf)
                    .forEach(b -> out.put(b.tradeDate(), b.close().doubleValue())));
    return out;
  }

  private Map<String, Object> valuation(InstrumentRow row, LocalDate asOf, List<String> caveats) {
    Map<String, Object> m = new LinkedHashMap<>();
    List<ValuationSnapshot> snaps =
        valuations.find(row.instrument(), row.id(), asOf.minusDays(10), asOf);
    ValuationSnapshot v = snaps.isEmpty() ? null : snaps.get(snaps.size() - 1);
    if (v == null) {
      caveats.add("判定日前 10 天内没有估值快照");
    } else {
      m.put("snapshotDate", v.asOf().atZone(zone).toLocalDate().toString());
      m.put(
          "marketCapUsdM",
          v.marketCap() == null
              ? null
              : v.marketCap().movePointLeft(6).setScale(0, RoundingMode.HALF_UP));
      m.put("peTtm", scale2(v.peTtm()));
      m.put("peStatic", scale2(v.pe()));
      m.put("pb", scale2(v.pb()));
      m.put("dividendYieldTtmPct", scale2(v.dividendYieldTtm()));
    }
    // Static PE 5-year percentile: daily_bar.pe is computed by the broker from the then-known EPS
    // of the previous fiscal year (measured: MSFT's implied EPS jumps the day after the annual
    // report, so no look-ahead)
    List<DailyBar> history = bars.find(row.instrument(), row.id(), asOf.minusYears(5), asOf);
    List<Double> pes =
        history.stream()
            .map(DailyBar::pe)
            .filter(p -> p != null && p.signum() > 0)
            .map(BigDecimal::doubleValue)
            .sorted()
            .toList();
    BigDecimal current = history.isEmpty() ? null : history.get(history.size() - 1).pe();
    if (current == null || current.signum() <= 0) {
      m.put("peStaticPercentile5y", null);
      // 0 and negative are not the same thing: the broker's daily-bar PE is 0 during loss-making
      // periods and for funds / Trusts (never negative); negative values only appear in the
      // valuation snapshot.
      // Previously 0 was also described as "negative (loss-making)", feeding the model a false
      // fundamental claim - and this caveat is itself a verifiable field path (found in the
      // 2026-09-25 full project review).
      if (current == null) {
        caveats.add("没有静态市盈率");
      } else if (current.signum() == 0) {
        caveats.add("日 K 的静态市盈率为 0：券商对亏损期与基金/Trust 都给 0，不代表估值为零，也不能据此断定亏损；不计算历史分位");
      } else {
        caveats.add("静态市盈率为负（亏损），不计算历史分位");
      }
    } else if (pes.size() < 500) {
      m.put("peStaticPercentile5y", null);
      caveats.add("静态市盈率历史不足两年，不计算分位");
    } else {
      double c = current.doubleValue();
      long below = pes.stream().filter(p -> p <= c).count();
      m.put("peStaticPercentile5y", r2(100.0 * below / pes.size()));
      m.put("peStatic5yMedian", r2(pes.get(pes.size() / 2)));
    }
    return m;
  }

  private Map<String, Object> financials(
      InstrumentRow row,
      LocalDate asOf,
      boolean pastDate,
      List<EarningsEvent> events,
      TreeSet<LocalDate> calendar,
      List<String> caveats) {
    Map<FinancialStatement, List<FinancialReport>> reports =
        new java.util.EnumMap<>(FinancialStatement.class);
    for (FinancialStatement st : FIELDS.keySet()) {
      reports.put(st, financials.find(row.instrument(), row.id(), st, 12));
    }
    // Judgement dates within 7 days of today skip the announcement check: everything stored is
    // public by now (note: for an asOf a few days back this can include a period announced after
    // asOf)
    Set<LocalDate> unpublished =
        pastDate ? unpublishedOn(asOf, reports, events, calendar) : Set.of();
    Map<String, Map<String, Object>> quarters = new LinkedHashMap<>();
    Map<String, Map<String, Object>> annual = new LinkedHashMap<>();
    for (Map.Entry<FinancialStatement, Map<Long, String>> fields : FIELDS.entrySet()) {
      for (FinancialReport rep : reports.get(fields.getKey())) {
        if (!rep.periodEnd().isBefore(asOf) || unpublished.contains(rep.periodEnd())) {
          continue;
        }
        boolean fy = rep.periodText().contains("FY");
        Map<String, Object> period =
            (fy ? annual : quarters)
                .computeIfAbsent(
                    rep.periodText(),
                    k -> {
                      Map<String, Object> x = new LinkedHashMap<>();
                      x.put("period", rep.periodText());
                      x.put("periodEnd", rep.periodEnd().toString());
                      return x;
                    });
        for (FinancialReport.Item item : rep.items()) {
          String key = fields.getValue().get(item.fieldId());
          if (key == null) {
            continue;
          }
          // Amounts in millions of USD, ratios with two decimals: a BigDecimal with trailing zeros
          // stripped is written by Jackson as 2.547E+9, which the model easily misreads
          boolean amount = AMOUNTS.contains(key);
          String outKey = amount ? key + "UsdM" : key;
          period.put(
              outKey,
              item.value() == null
                  ? null
                  : amount
                      ? item.value().movePointLeft(6).setScale(1, RoundingMode.HALF_UP)
                      : item.value().setScale(2, RoundingMode.HALF_UP));
          if (WITH_YOY.contains(key)) {
            period.put(
                key + "YoyPct",
                item.yoy() == null ? null : item.yoy().setScale(2, RoundingMode.HALF_UP));
          }
        }
      }
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("quarters", sortedDesc(quarters, 8));
    m.put("annual", sortedDesc(annual, 3));
    if (quarters.isEmpty() && annual.isEmpty()) {
      caveats.add("没有财报数据（基金或券商未提供）");
    }
    return m;
  }

  private static List<Map<String, Object>> sortedDesc(
      Map<String, Map<String, Object>> periods, int limit) {
    return periods.values().stream()
        .sorted((a, b) -> ((String) b.get("periodEnd")).compareTo((String) a.get("periodEnd")))
        .limit(limit)
        .toList();
  }

  /**
   * Periods not yet announced on the judgement date (stored period ends). Periods are deduplicated
   * by period end and resolved together; an annual report shares its period end with Q4 and uses
   * the Q4 deadline. With no announcement dates (ETFs, names outside the selection universe)
   * everything falls back to the statutory deadline.
   */
  static Set<LocalDate> unpublishedOn(
      LocalDate asOf,
      Map<FinancialStatement, List<FinancialReport>> reports,
      List<EarningsEvent> events,
      TreeSet<LocalDate> calendar) {
    Map<LocalDate, Boolean> q4 = new java.util.TreeMap<>();
    for (List<FinancialReport> list : reports.values()) {
      for (FinancialReport rep : list) {
        boolean fourth = rep.periodText().contains("FY") || rep.periodText().endsWith("Q4");
        q4.merge(rep.periodEnd(), fourth, Boolean::logicalOr);
      }
    }
    // The stored period end is one day earlier than the true one (§25.5); announcement matching
    // uses the true period end
    List<QuarterAvailability.Period> periods =
        q4.entrySet().stream()
            .map(e -> new QuarterAvailability.Period(e.getKey().plusDays(1), e.getValue()))
            .toList();
    List<LocalDate> available =
        QuarterAvailability.availableDates(
            periods, events.stream().map(EarningsEvent::pubDate).toList(), calendar);
    // An announcement on the judgement date that is after market or of unknown session counts as
    // not yet announced, the same rule as calendarHint (APH 2026-07-29, unknown session: the hint
    // said not reflected while the statements were already included)
    boolean pendingOnAsOf =
        events.stream()
            .anyMatch(e -> e.pubDate().equals(asOf) && !EarningsCalendarHint.reflected(e, asOf));
    Set<LocalDate> out = new HashSet<>();
    for (int i = 0; i < periods.size(); i++) {
      if (available.get(i).isAfter(asOf) || (pendingOnAsOf && available.get(i).equals(asOf))) {
        out.add(periods.get(i).periodEnd().minusDays(1));
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static LocalDate latestQuarterEnd(Map<String, Object> fin) {
    List<Map<String, Object>> quarters = (List<Map<String, Object>>) fin.get("quarters");
    return quarters.isEmpty() ? null : LocalDate.parse((String) quarters.get(0).get("periodEnd"));
  }

  private Map<String, Object> profile(InstrumentRow row, List<String> caveats) {
    Map<String, Object> m = new LinkedHashMap<>();
    CompanyProfile p = profiles.find(row.instrument(), row.id()).orElse(null);
    String text = p == null ? null : p.fields().get("公司简介");
    if (text == null) {
      caveats.add("没有公司简介");
    } else {
      m.put("description", text.length() > 800 ? text.substring(0, 800) + "…" : text);
      m.put("employees", p.fields().get("员工数量"));
    }
    return m;
  }

  private static BigDecimal scale2(BigDecimal v) {
    return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal r2(double v) {
    return Double.isNaN(v) || Double.isInfinite(v)
        ? null
        : BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
  }

  private static Map<Long, String> linked(Object... kv) {
    Map<Long, String> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((Long) kv[i], (String) kv[i + 1]);
    }
    return m;
  }
}
