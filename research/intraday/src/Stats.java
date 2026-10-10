import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;

/**
 * 预登记的判定（ARCHITECTURE §24.6）：配对差值、按信号日成组的自助法 10000 次、98.3% 置信区间（三个检验 Bonferroni）、
 * 按信号日对半切开看前后两半方向。门槛 +0.05R。
 */
final class Stats {

    static final double THRESHOLD = 0.05;
    static final double ALPHA = 0.05 / 3;
    static final int RESAMPLES = 10_000;
    static final long SEED = 20261010L;

    record Pair(LocalDate date, double diff) {
    }

    record Verdict(int n, int clusters, double mean, double lo, double hi, double firstHalf, double secondHalf,
                   boolean meetsThreshold, boolean ciExcludesZero, boolean halvesAgree) {
        boolean adopt() {
            return meetsThreshold && ciExcludesZero && halvesAgree;
        }

        String describe() {
            return String.format("n=%d（%d 个信号日） 平均 %+.4fR  98.3%%CI [%+.4f, %+.4f]  前半 %+.4f  后半 %+.4f  → %s"
                            + "（门槛 %s、区间不跨 0 %s、两半同向 %s）",
                    n, clusters, mean, lo, hi, firstHalf, secondHalf, adopt() ? "做" : "不做",
                    ok(meetsThreshold), ok(ciExcludesZero), ok(halvesAgree));
        }

        private static String ok(boolean b) {
            return b ? "✓" : "✗";
        }
    }

    private Stats() {
    }

    static Verdict judge(List<Pair> pairs) {
        Map<LocalDate, List<Double>> byDate = new TreeMap<>();
        for (Pair p : pairs) {
            byDate.computeIfAbsent(p.date(), d -> new ArrayList<>()).add(p.diff());
        }
        List<double[]> clusters = new ArrayList<>();
        for (List<Double> v : byDate.values()) {
            clusters.add(new double[]{v.stream().mapToDouble(Double::doubleValue).sum(), v.size()});
        }
        double mean = pairs.stream().mapToDouble(Pair::diff).average().orElse(Double.NaN);

        SplittableRandom rnd = new SplittableRandom(SEED);
        double[] boot = new double[RESAMPLES];
        int k = clusters.size();
        for (int b = 0; b < RESAMPLES; b++) {
            double sum = 0;
            double n = 0;
            for (int j = 0; j < k; j++) {
                double[] c = clusters.get(rnd.nextInt(k));
                sum += c[0];
                n += c[1];
            }
            boot[b] = sum / n;
        }
        Arrays.sort(boot);
        double lo = boot[(int) Math.floor(ALPHA / 2 * RESAMPLES)];
        double hi = boot[(int) Math.ceil((1 - ALPHA / 2) * RESAMPLES) - 1];

        // 按信号日对半：同一天的信号不拆到两边
        List<Pair> sorted = new ArrayList<>(pairs);
        sorted.sort((a, b) -> a.date().compareTo(b.date()));
        LocalDate cut = sorted.get(sorted.size() / 2).date();
        double first = sorted.stream().filter(p -> p.date().isBefore(cut)).mapToDouble(Pair::diff).average().orElse(Double.NaN);
        double second = sorted.stream().filter(p -> !p.date().isBefore(cut)).mapToDouble(Pair::diff).average().orElse(Double.NaN);

        return new Verdict(pairs.size(), k, mean, lo, hi, first, second,
                mean >= THRESHOLD, lo > 0 || hi < 0, Math.signum(first) == Math.signum(second) && Math.signum(first) == Math.signum(mean));
    }
}
