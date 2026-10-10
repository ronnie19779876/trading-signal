import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.signal.Indicators;
import org.jdkxx.trader.domain.signal.PaperTrade;
import org.jdkxx.trader.domain.signal.SentinelThresholds;
import org.jdkxx.trader.domain.signal.SignalBar;
import org.jdkxx.trader.domain.signal.SignalTrades;
import org.jdkxx.trader.domain.signal.StructuralAdjustment;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * H2：盘中初始止损 vs 收盘止损（ARCHITECTURE §24.6），只用日 K。
 *
 * <p>两道关，任何一道不过就不出结论：
 * <ol>
 *   <li>生产入口 {@link SignalTrades#simulate} 对开发库重算，必须与冻结清单逐笔相同（出场日、原因、R）；</li>
 *   <li>研究实现关掉改动（{@code intraday=false}）必须与 1 逐笔相同。</li>
 * </ol>
 * 用法：run.sh H2IntradayStop [signals.csv，缺省 data/signals.csv]
 */
public final class H2IntradayStop {

    /** 纸面交易走到的日期；高于开发库最后一根 K 线即可（结构换算的锚点只差一个常数倍，R 不变）。 */
    static final LocalDate THROUGH = LocalDate.parse("2026-10-09");

    public static void main(String[] args) throws Exception {
        Path in = Path.of(args.length > 0 ? args[0] : "data/signals.csv");
        List<Map<String, String>> signals = Lab.readCsv(in);
        Map<String, List<Map<String, String>>> bySymbol = new TreeMap<>();
        for (Map<String, String> s : signals) {
            bySymbol.computeIfAbsent(s.get("symbol"), k -> new ArrayList<>()).add(s);
        }
        SentinelThresholds th = SentinelThresholds.V1;
        PaperTrade.Rules rules = PaperTrade.Rules.of(th);

        List<List<?>> rows = new ArrayList<>();
        List<String> gate1 = new ArrayList<>();
        List<String> gate2 = new ArrayList<>();
        List<Stats.Pair> closedPairs = new ArrayList<>();
        List<Stats.Pair> markedPairs = new ArrayList<>();
        Map<String, double[]> byAltReason = new LinkedHashMap<>();
        int excluded = 0;
        int intradayExits = 0;
        int whipsaws = 0;
        int entryDayGap = 0;

        try (DevDb db = new DevDb()) {
            for (var e : bySymbol.entrySet()) {
                Instrument ins = new Instrument(Market.US, e.getKey());
                long id = db.instrumentId(e.getKey());
                LocalDate earliest = e.getValue().stream().map(s -> LocalDate.parse(s.get("signalDate"))).min(LocalDate::compareTo).orElseThrow();
                List<DailyBar> raw = db.bars(ins, id, earliest.minusDays(th.windowCalendarDays()), THROUGH);
                List<RehabFactor> factors = db.factors(ins, id);
                Set<LocalDate> tradingDays = new HashSet<>(db.tradingDays(raw.get(0).tradeDate(), THROUGH));

                for (Map<String, String> s : e.getValue()) {
                    LocalDate signalDate = LocalDate.parse(s.get("signalDate"));
                    double close = Double.parseDouble(s.get("close"));
                    double stop = Double.parseDouble(s.get("stop"));
                    String key = e.getKey() + " " + signalDate;

                    // 关 1：生产入口重算 = 冻结清单
                    PaperTrade.Result official = SignalTrades.simulate(raw, factors, tradingDays, signalDate, close, stop, THROUGH, th, rules);
                    if (!sameAsFrozen(official, s)) {
                        gate1.add(key + " 冻结 " + s.get("exitDate") + "/" + s.get("reason") + "/" + s.get("r")
                                + " 重算 " + official.exitDate() + "/" + official.reason() + "/" + official.r());
                        continue;
                    }

                    // 与 SignalTrades.simulate 相同的取数与换算
                    LocalDate from = signalDate.minusDays(th.windowCalendarDays());
                    List<DailyBar> slice = raw.stream()
                            .filter(b -> !b.tradeDate().isBefore(from) && !b.tradeDate().isAfter(THROUGH) && tradingDays.contains(b.tradeDate()))
                            .toList();
                    List<SignalBar> series = StructuralAdjustment.apply(slice, factors, THROUGH).bars();
                    int index = -1;
                    for (int i = 0; i < series.size(); i++) {
                        if (series.get(i).date().equals(signalDate)) {
                            index = i;
                        }
                    }
                    double[] atr = Indicators.wilderAtr(series.stream().mapToDouble(SignalBar::high).toArray(),
                            series.stream().mapToDouble(SignalBar::low).toArray(),
                            series.stream().mapToDouble(SignalBar::close).toArray(), th.atrPeriod());
                    double scale = series.get(index).close() / close;
                    double scaledStop = stop * scale;

                    // 关 2：研究实现关掉改动 = 生产入口
                    IntradayStopTrade.Result base = IntradayStopTrade.simulate(series, atr, index, scaledStop, rules, false);
                    if (!sameAsOfficial(base, official)) {
                        gate2.add(key + " 生产 " + official.exitDate() + "/" + official.reason() + "/" + official.r()
                                + " 研究 " + base.exitDate() + "/" + base.reason() + "/" + base.r());
                        continue;
                    }

                    IntradayStopTrade.Result alt = IntradayStopTrade.simulate(series, atr, index, scaledStop, rules, true);
                    double entry = series.get(index + 1).open();
                    double risk = series.get(index).close() - scaledStop;
                    if (entry <= scaledStop) {
                        entryDayGap++;
                    }
                    boolean intraday = alt.reason() == IntradayStopTrade.Exit.INTRADAY_GAP || alt.reason() == IntradayStopTrade.Exit.INTRADAY_TOUCH;
                    if (intraday) {
                        intradayExits++;
                        if (alt.whipsaw()) {
                            whipsaws++;
                        }
                    }
                    double baseMarked = base.r() != null ? base.r() : (base.lastClose() - entry) / risk;
                    double altMarked = alt.r() != null ? alt.r() : (alt.lastClose() - entry) / risk;
                    markedPairs.add(new Stats.Pair(signalDate, altMarked - baseMarked));
                    Double diff = null;
                    if (base.r() != null && alt.r() != null) {
                        diff = alt.r() - base.r();
                        closedPairs.add(new Stats.Pair(signalDate, diff));
                        double[] acc = byAltReason.computeIfAbsent(alt.reason() + (alt.reason() == base.reason() ? "（未变）" : ""), k -> new double[2]);
                        acc[0]++;
                        acc[1] += diff;
                    } else {
                        excluded++;
                    }
                    rows.add(Arrays.asList(e.getKey(), signalDate, base.reason(), base.exitDate(), base.r(),
                            alt.reason(), alt.exitDate(), alt.r(), diff, alt.whipsaw()));
                }
            }
        }

        Lab.writeCsv(Lab.DATA.resolve("h2-" + in.getFileName()), List.of("symbol", "signalDate", "baseReason", "baseExitDate", "baseR",
                "altReason", "altExitDate", "altR", "diff", "whipsaw"), rows);

        System.out.printf("信号 %d 条；关 1 不符 %d、关 2 不符 %d%n", signals.size(), gate1.size(), gate2.size());
        gate1.stream().limit(20).forEach(g -> System.out.println("  关1 " + g));
        gate2.stream().limit(20).forEach(g -> System.out.println("  关2 " + g));
        if (!gate1.isEmpty() || !gate2.isEmpty()) {
            System.out.println("关没过，不出结论。");
            return;
        }
        System.out.println("H2 主检验（两臂都已收场）：" + Stats.judge(closedPairs).describe());
        System.out.printf("  剔除（至少一臂未收场）%d 条%n", excluded);
        System.out.println("敏感性（未收场按最后收盘计，全部 " + markedPairs.size() + " 条）：" + Stats.judge(markedPairs).describe());
        System.out.printf("盘中止损出场 %d 条，其中被洗出去（当天收盘在止损之上）%d 条（%.1f%%）；入场当天开盘就低于止损 %d 条%n",
                intradayExits, whipsaws, 100.0 * whipsaws / Math.max(1, intradayExits), entryDayGap);
        System.out.println("按备选出场原因拆开（条数 / 平均差值）：");
        byAltReason.forEach((k, v) -> System.out.printf("  %-24s %5.0f  %+.4fR%n", k, v[0], v[1] / v[0]));
    }

    private static boolean sameAsFrozen(PaperTrade.Result r, Map<String, String> s) {
        return r != null && String.valueOf(r.exitDate()).equals(String.valueOf(s.get("exitDate")))
                && r.reason().name().equals(s.get("reason"))
                && close(r.r(), s.get("r") == null ? null : Double.parseDouble(s.get("r")));
    }

    private static boolean sameAsOfficial(IntradayStopTrade.Result b, PaperTrade.Result o) {
        return String.valueOf(b.exitDate()).equals(String.valueOf(o.exitDate()))
                && b.reason().name().equals(o.reason().name())
                && close(b.r(), o.r());
    }

    private static boolean close(Double a, Double b) {
        if (a == null || b == null) {
            return a == b;
        }
        return Math.abs(a - b) <= 1e-9 * Math.max(1, Math.abs(b));
    }
}
