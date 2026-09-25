package org.jdkxx.trader.core.signal.ai;

import org.jdkxx.trader.domain.CompanyProfile;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.ValuationSnapshot;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发给模型的输入：全部由代码算好，LinkedHashMap 保证键序固定（前缀缓存要命中、输入哈希要稳定），数值统一取有限位小数。
 *
 * <p>刻意不含：持仓数量、成本、净值、账户信息，也不说是否持有（用户 2026-09-17 决定）。
 * 财报只取期末早于判定日的；判定日早于今天 7 天以上（手工分析过去的日子）时只取期末早于判定日 45 天的，
 * 因为库里没有财报公布日，更近的那期在判定日可能还没公布。
 */
public class SignalPayloadBuilder {

    /** 财报白名单：（报表，字段编号）→ 输出键。字段编号的含义随报表类型不同，必须按报表定位（2026-09-17 开发库 MSFT 核对）。 */
    static final Map<FinancialStatement, Map<Long, String>> FIELDS = new java.util.EnumMap<>(FinancialStatement.class);

    static {
        // 键序决定输入 JSON 的字段顺序：必须固定（前缀缓存、输入哈希），所以用 EnumMap + LinkedHashMap，不用 Map.of
        FIELDS.put(FinancialStatement.INCOME, linked(8001L, "revenue", 8004L, "grossProfit", 8017L, "operatingIncome",
                8043L, "netIncomeToParent", 8048L, "dilutedEps"));
        FIELDS.put(FinancialStatement.CASH_FLOW, linked(8015L, "operatingCashFlow", 8046L, "capitalExpenditure", 8072L, "freeCashFlow"));
        FIELDS.put(FinancialStatement.MAIN_INDEX, linked(14002L, "grossMargin", 14003L, "operatingMargin", 14005L, "netMarginToParent",
                14019L, "interestBearingDebtRatio", 14020L, "currentRatio", 14029L, "roe", 14031L, "roic",
                14032L, "fcfToRevenue", 14039L, "revenueCagr3y", 14041L, "netIncomeCagr3y"));
    }

    /** 金额字段（输出为百万美元，键名加 UsdM） */
    static final Set<String> AMOUNTS = Set.of("revenue", "grossProfit", "operatingIncome", "netIncomeToParent",
            "operatingCashFlow", "capitalExpenditure", "freeCashFlow");
    /** 带同比的字段 */
    static final Set<String> WITH_YOY = Set.of("revenue", "operatingIncome", "netIncomeToParent", "dilutedEps", "freeCashFlow");

    private final InstrumentRepository instruments;
    private final IndexConstituentRepository constituents;
    private final DailyBarRepository bars;
    private final RehabFactorRepository rehabs;
    private final TradingDayRepository days;
    private final ValuationRepository valuations;
    private final FinancialRepository financials;
    private final CompanyProfileRepository profiles;
    private final ZoneId zone;
    private final SentinelThresholds th = SentinelThresholds.V1;

    public SignalPayloadBuilder(InstrumentRepository instruments, IndexConstituentRepository constituents, DailyBarRepository bars,
                                RehabFactorRepository rehabs, TradingDayRepository days, ValuationRepository valuations,
                                FinancialRepository financials, CompanyProfileRepository profiles, ZoneId zone) {
        this.instruments = instruments;
        this.constituents = constituents;
        this.bars = bars;
        this.rehabs = rehabs;
        this.days = days;
        this.valuations = valuations;
        this.financials = financials;
        this.profiles = profiles;
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
        Map<String, Object> fin = financials(row, asOf, today, caveats);
        root.put("financials", fin);
        root.put("calendarHint", calendarHint(fin, asOf));
        root.put("profile", profile(row, caveats));
        root.put("caveats", caveats);
        return root;
    }

    private Map<String, Object> meta(InstrumentRow row, LocalDate asOf) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", row.symbol());
        m.put("name", row.name());
        m.put("nameCn", row.nameCn());
        List<ConstituentRow> idx = constituents.historyOf(row.id()).stream().filter(c -> c.until() == null).toList();
        m.put("indexes", idx.stream().map(c -> c.index().name()).distinct().toList());
        m.put("sector", idx.stream().map(ConstituentRow::sector).filter(s -> s != null).findFirst().orElse(null));
        m.put("subIndustry", idx.stream().map(ConstituentRow::subIndustry).filter(s -> s != null).findFirst().orElse(null));
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
        GateResult risk = e.gates().stream().filter(g -> g.gate() == GateResult.Gate.RISK).findFirst().orElseThrow();
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
        Map<LocalDate, Double> spy = spyCloses(asOf);
        for (int n : new int[]{20, 60, 250}) {
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
        instruments.find(Instrument.us("SPY")).ifPresent(spy ->
                bars.find(spy.instrument(), spy.id(), asOf.minusDays(400), asOf)
                        .forEach(b -> out.put(b.tradeDate(), b.close().doubleValue())));
        return out;
    }

    private Map<String, Object> valuation(InstrumentRow row, LocalDate asOf, List<String> caveats) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<ValuationSnapshot> snaps = valuations.find(row.instrument(), row.id(), asOf.minusDays(10), asOf);
        ValuationSnapshot v = snaps.isEmpty() ? null : snaps.get(snaps.size() - 1);
        if (v == null) {
            caveats.add("判定日前 10 天内没有估值快照");
        } else {
            m.put("snapshotDate", v.asOf().atZone(zone).toLocalDate().toString());
            m.put("marketCapUsdM", v.marketCap() == null ? null : v.marketCap().movePointLeft(6).setScale(0, RoundingMode.HALF_UP));
            m.put("peTtm", scale2(v.peTtm()));
            m.put("peStatic", scale2(v.pe()));
            m.put("pb", scale2(v.pb()));
            m.put("dividendYieldTtmPct", scale2(v.dividendYieldTtm()));
        }
        // 静态市盈率 5 年分位：daily_bar.pe 是券商按当时已知的上一财年 EPS 算的（实测 MSFT 隐含 EPS 在年报次日跳变，无前视）
        List<DailyBar> history = bars.find(row.instrument(), row.id(), asOf.minusYears(5), asOf);
        List<Double> pes = history.stream().map(DailyBar::pe).filter(p -> p != null && p.signum() > 0)
                .map(BigDecimal::doubleValue).sorted().toList();
        BigDecimal current = history.isEmpty() ? null : history.get(history.size() - 1).pe();
        if (current == null || current.signum() <= 0) {
            m.put("peStaticPercentile5y", null);
            // 0 与负数不是一回事：券商的日 K 市盈率在亏损期与基金/Trust 上给 0（不给负数），
            // 负数只出现在估值快照。原先把 0 也说成「为负（亏损）」，等于把一条虚假的基本面断言
            // 喂给模型，而且这条 caveat 自身是可核对的字段路径（2026-09-25 全项目审查发现）。
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

    private Map<String, Object> financials(InstrumentRow row, LocalDate asOf, LocalDate today, List<String> caveats) {
        LocalDate cutoff = asOf.isBefore(today.minusDays(7)) ? asOf.minusDays(45) : asOf;
        Map<String, Map<String, Object>> quarters = new LinkedHashMap<>();
        Map<String, Map<String, Object>> annual = new LinkedHashMap<>();
        for (Map.Entry<FinancialStatement, Map<Long, String>> fields : FIELDS.entrySet()) {
            for (FinancialReport rep : financials.find(row.instrument(), row.id(), fields.getKey(), 12)) {
                if (!rep.periodEnd().isBefore(cutoff)) {
                    continue;
                }
                boolean fy = rep.periodText().contains("FY");
                Map<String, Object> period = (fy ? annual : quarters).computeIfAbsent(rep.periodText(), k -> {
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
                    // 金额换成百万美元、比率保留两位：BigDecimal 去尾零后 Jackson 会写成 2.547E+9，模型容易读错
                    boolean amount = AMOUNTS.contains(key);
                    String outKey = amount ? key + "UsdM" : key;
                    period.put(outKey, item.value() == null ? null : amount
                            ? item.value().movePointLeft(6).setScale(1, RoundingMode.HALF_UP)
                            : item.value().setScale(2, RoundingMode.HALF_UP));
                    if (WITH_YOY.contains(key)) {
                        period.put(key + "YoyPct", item.yoy() == null ? null : item.yoy().setScale(2, RoundingMode.HALF_UP));
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

    private static List<Map<String, Object>> sortedDesc(Map<String, Map<String, Object>> periods, int limit) {
        return periods.values().stream()
                .sorted((a, b) -> ((String) b.get("periodEnd")).compareTo((String) a.get("periodEnd")))
                .limit(limit).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> calendarHint(Map<String, Object> fin, LocalDate asOf) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> quarters = (List<Map<String, Object>>) fin.get("quarters");
        if (quarters.isEmpty()) {
            m.put("latestQuarterEnd", null);
            return m;
        }
        LocalDate end = LocalDate.parse((String) quarters.get(0).get("periodEnd"));
        long since = ChronoUnit.DAYS.between(end, asOf);
        m.put("latestQuarterEnd", end.toString());
        m.put("daysSinceLatestQuarterEnd", since);
        m.put("note", "系统没有财报日历。美股季报通常在期末后约 25~45 天公布、下一期期末约在最近期末后 91 天；"
                + "距最近期末 " + since + " 天，据此估计下一次财报大约在判定日后 " + Math.max(0, 91 + 25 - since) + "~"
                + Math.max(0, 91 + 45 - since) + " 天");
        return m;
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
        return Double.isNaN(v) || Double.isInfinite(v) ? null : BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<Long, String> linked(Object... kv) {
        Map<Long, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((Long) kv[i], (String) kv[i + 1]);
        }
        return m;
    }
}
