import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * H1：入场时点（ARCHITECTURE §24.6），富途 1 分钟线（终点标时：09:31 那根覆盖 09:30 ~ 09:31）。
 *
 * <ul>
 *   <li>基准：次日开盘（冻结清单的 entry，判定日口径）；</li>
 *   <li>H1a：次日 09:30 ~ 10:00 成交量加权均价（标时 09:31 ~ 10:00 的 Σ成交额 ÷ Σ成交量）；</li>
 *   <li>H1b：信号当天 18:15 ~ 20:00 成交量加权均价（标时 18:16 ~ 20:00）；该时段无成交记为未成交；</li>
 *   <li>配对差值 = (基准 − 备选) ÷ R，正数表示备选买得更便宜；分钟线不复权，按「冻结入场价 ÷ 入场日原始开盘」折回判定日口径。</li>
 * </ul>
 * 关：入场日首根分钟线的开盘必须等于开发库日 K 的开盘（§24.2 实测逐位一致），否则不出结论。
 * <b>数据没取齐不出判定</b>——只看一部分就下结论是选择性停止，预登记不允许。
 */
public final class H1EntryTiming {

    static final LocalTime A_FROM = LocalTime.of(9, 31);
    static final LocalTime A_TO = LocalTime.of(10, 0);
    static final LocalTime B_FROM = LocalTime.of(18, 16);
    static final LocalTime B_TO = LocalTime.of(20, 0);
    static final LocalTime OPEN_BAR = LocalTime.of(9, 31);

    record Bar(double open, double volume, double turnover) {
    }

    public static void main(String[] args) throws Exception {
        Map<String, TreeSet<LocalDate[]>> windows = MinuteFetch.windows();
        List<String> missing = windows.keySet().stream().filter(s -> !Files.exists(MinuteFetch.MINUTE.resolve(s + ".csv"))).toList();
        if (!missing.isEmpty()) {
            System.out.printf("分钟线还缺 %d / %d 只（%s…），不出判定。%n", missing.size(), windows.size(),
                    String.join(" ", missing.subList(0, Math.min(5, missing.size()))));
            return;
        }

        List<Map<String, String>> signals = Lab.readCsv(Lab.DATA.resolve("signals.csv")).stream()
                .filter(s -> "true".equals(s.get("h1"))).toList();
        List<Stats.Pair> a = new ArrayList<>();
        List<Stats.Pair> b = new ArrayList<>();
        List<Double> aBp = new ArrayList<>();
        List<Double> bBp = new ArrayList<>();
        List<String> gate = new ArrayList<>();
        int noA = 0;
        int noB = 0;
        int rescaled = 0;
        List<List<?>> rows = new ArrayList<>();

        Map<String, Map<LocalDate, TreeMap<LocalTime, Bar>>> cache = new TreeMap<>();
        try (DevDb db = new DevDb()) {
            for (Map<String, String> s : signals) {
                String symbol = s.get("symbol");
                Map<LocalDate, TreeMap<LocalTime, Bar>> m = cache.computeIfAbsent(symbol, H1EntryTiming::load);
                LocalDate signalDate = LocalDate.parse(s.get("signalDate"));
                LocalDate entryDate = LocalDate.parse(s.get("entryDate"));
                double entry = Double.parseDouble(s.get("entry"));
                double risk = Double.parseDouble(s.get("risk"));

                TreeMap<LocalTime, Bar> day = m.get(entryDate);
                Bar first = day == null ? null : day.get(OPEN_BAR);
                List<DailyBar> daily = db.bars(new Instrument(Market.US, symbol), db.instrumentId(symbol), entryDate, entryDate);
                if (first == null || daily.isEmpty()
                        || Math.abs(first.open() - daily.get(0).open().doubleValue()) > 1e-6 * first.open()) {
                    gate.add(symbol + " " + entryDate + " 分钟首根开盘 " + (first == null ? "无" : first.open())
                            + " 日 K 开盘 " + (daily.isEmpty() ? "无" : daily.get(0).open()));
                    continue;
                }
                double k = entry / first.open();
                if (Math.abs(k - 1) > 1e-9) {
                    rescaled++;
                }
                Double vwapA = vwap(day, A_FROM, A_TO);
                Double vwapB = vwap(m.get(signalDate), B_FROM, B_TO);
                Double diffA = null;
                Double diffB = null;
                if (vwapA == null) {
                    noA++;
                } else {
                    diffA = (entry - vwapA * k) / risk;
                    a.add(new Stats.Pair(signalDate, diffA));
                    aBp.add((entry - vwapA * k) / entry * 1e4);
                }
                if (vwapB == null) {
                    noB++;
                } else {
                    // 信号日的原始价就是判定日口径
                    diffB = (entry - vwapB) / risk;
                    b.add(new Stats.Pair(signalDate, diffB));
                    bBp.add((entry - vwapB) / entry * 1e4);
                }
                rows.add(java.util.Arrays.asList(symbol, signalDate, entry, risk, vwapA == null ? null : vwapA * k, vwapB, diffA, diffB));
            }
        }
        Lab.writeCsv(Lab.DATA.resolve("h1.csv"), List.of("symbol", "signalDate", "entry", "risk", "vwapA", "vwapB", "diffA", "diffB"), rows);

        System.out.printf("H1 信号 %d 条；关（首根开盘 = 日 K 开盘）不符 %d 条；入场日与判定日之间有换算的 %d 条%n",
                signals.size(), gate.size(), rescaled);
        gate.stream().limit(20).forEach(g -> System.out.println("  关 " + g));
        if (!gate.isEmpty()) {
            System.out.println("关没过，不出结论。");
            return;
        }
        System.out.println("H1a 次日 09:30~10:00 均价：" + Stats.judge(a).describe());
        System.out.printf("  无成交剔除 %d 条；以基点计平均 %+.1fbp、中位 %+.1fbp%n", noA, mean(aBp), median(aBp));
        System.out.println("H1b 信号日盘后 18:15~20:00 均价：" + Stats.judge(b).describe());
        System.out.printf("  成交率 %d / %d（%.1f%%）；以基点计平均 %+.1fbp、中位 %+.1fbp ——盈亏平衡成本即此平均值（单边，正数才有意义）%n",
                b.size(), b.size() + noB, 100.0 * b.size() / (b.size() + noB), mean(bBp), median(bBp));
        controls(windows, signals);
    }

    /** 附带报告（不参与判定）：同一批标的随机非信号日上的同一差值，只能以基点计（非信号日没有 R）。 */
    private static void controls(Map<String, TreeSet<LocalDate[]>> windows, List<Map<String, String>> signals) {
        java.util.Set<String> signalKeys = new java.util.HashSet<>();
        signals.forEach(s -> signalKeys.add(s.get("symbol") + " " + s.get("signalDate")));
        List<Double> a = new ArrayList<>();
        List<Double> b = new ArrayList<>();
        for (var e : windows.entrySet()) {
            Map<LocalDate, TreeMap<LocalTime, Bar>> m = load(e.getKey());
            for (LocalDate[] w : e.getValue()) {
                if (signalKeys.contains(e.getKey() + " " + w[0])) {
                    continue;
                }
                TreeMap<LocalTime, Bar> next = m.get(w[1]);
                Bar first = next == null ? null : next.get(OPEN_BAR);
                if (first == null) {
                    continue;
                }
                Double va = vwap(next, A_FROM, A_TO);
                Double vb = vwap(m.get(w[0]), B_FROM, B_TO);
                if (va != null) {
                    a.add((first.open() - va) / first.open() * 1e4);
                }
                if (vb != null) {
                    b.add((first.open() - vb) / first.open() * 1e4);
                }
            }
        }
        System.out.printf("对照（随机非信号日，%d 对）：H1a 平均 %+.1fbp、中位 %+.1fbp；H1b 平均 %+.1fbp、中位 %+.1fbp（成交 %d 对）%n",
                a.size(), mean(a), median(a), mean(b), median(b), b.size());
    }

    private static Double vwap(TreeMap<LocalTime, Bar> day, LocalTime from, LocalTime to) {
        if (day == null) {
            return null;
        }
        double v = 0;
        double t = 0;
        for (Bar x : day.subMap(from, true, to, true).values()) {
            v += x.volume();
            t += x.turnover();
        }
        return v > 0 && t > 0 ? t / v : null;
    }

    private static Map<LocalDate, TreeMap<LocalTime, Bar>> load(String symbol) {
        try {
            Map<LocalDate, TreeMap<LocalTime, Bar>> out = new TreeMap<>();
            List<String> lines = Files.readAllLines(MinuteFetch.MINUTE.resolve(symbol + ".csv"));
            for (String line : lines.subList(1, lines.size())) {
                String[] f = line.split(",");
                LocalDate d = LocalDate.parse(f[0].substring(0, 10));
                LocalTime t = LocalTime.parse(f[0].substring(11));
                // 两段可能重叠（相邻两天都有信号），同一分钟只留一根
                out.computeIfAbsent(d, x -> new TreeMap<>()).put(t, new Bar(Double.parseDouble(f[1]), Double.parseDouble(f[5]), Double.parseDouble(f[6])));
            }
            return out;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static double mean(List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private static double median(List<Double> v) {
        if (v.isEmpty()) {
            return Double.NaN;
        }
        double[] s = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return s.length % 2 == 1 ? s[s.length / 2] : (s[s.length / 2 - 1] + s[s.length / 2]) / 2;
    }
}
