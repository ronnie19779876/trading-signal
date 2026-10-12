package org.jdkxx.trader.core.selection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.selection.Factor;
import org.jdkxx.trader.domain.selection.Metric;
import org.jdkxx.trader.domain.selection.Rating;
import org.jdkxx.trader.domain.selection.SelectionModel;
import org.jdkxx.trader.domain.selection.StockInputs;
import org.jdkxx.trader.domain.selection.StockScore;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.selection.SelectionScoreRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 量化选股打分（§25.4、§25.7）：单日评分与历史重放写同一张表，同一套纯函数。
 * {@code sb_streak} 依赖前一个评分日，重放必须按日期顺序算，范围起点之前的 streak 从库里接。
 */
public class SelectionScoringService {

    private final SelectionDataLoader loader;
    private final SelectionScoreRepository scores;
    private final TradingDayRepository days;
    private final Supplier<LocalDate> settledCutoff;
    private final ObjectMapper json;

    public SelectionScoringService(SelectionDataLoader loader, SelectionScoreRepository scores, TradingDayRepository days,
                                   Supplier<LocalDate> settledCutoff, ObjectMapper json) {
        this.loader = loader;
        this.scores = scores;
        this.days = days;
        this.settledCutoff = settledCutoff;
        this.json = json;
    }

    /** 给一个交易日打分（覆盖当天已有结果）。 */
    public String scoreDay(LocalDate date, JobContext ctx) {
        requireScorable(date);
        ctx.progress("加载输入");
        SelectionDataset data = loader.load(date, date);
        Map<Long, Integer> prev = scores.latestBefore(SelectionModel.VERSION, date)
                .map(d -> scores.streaks(SelectionModel.VERSION, d)).orElse(Map.of());
        List<StockInputs> in = data.inputsOn(date);
        List<StockScore> result = SelectionModel.score(in);
        scores.replaceDay(SelectionModel.VERSION, date, rows(date, in, result, prev));
        return summary(date, result);
    }

    /** 历史重放 [from, to] 的每个交易日（只到已收盘落定的那天）。 */
    public String replay(LocalDate from, LocalDate to, JobContext ctx) {
        LocalDate end = to.isAfter(settledCutoff.get()) ? settledCutoff.get() : to;
        List<LocalDate> tradingDays = days.between(Market.US, from, end);
        if (tradingDays.isEmpty()) {
            throw new IllegalArgumentException(from + " ~ " + end + " 之间没有交易日（或交易日历没覆盖）");
        }
        ctx.progress("加载输入 " + from + " ~ " + end);
        SelectionDataset data = loader.load(tradingDays.getFirst(), tradingDays.getLast());
        Map<Long, Integer> streak = new HashMap<>(scores.latestBefore(SelectionModel.VERSION, tradingDays.getFirst())
                .map(d -> scores.streaks(SelectionModel.VERSION, d)).orElse(Map.of()));
        int done = 0;
        long rated = 0;
        for (LocalDate d : tradingDays) {
            if (ctx.cancelled()) {
                ctx.partial("已取消，停在 " + d);
                break;
            }
            List<StockInputs> in = data.inputsOn(d);
            List<StockScore> result = SelectionModel.score(in);
            List<SelectionScoreRepository.ScoreRow> rows = rows(d, in, result, streak);
            scores.replaceDay(SelectionModel.VERSION, d, rows);
            streak.clear();
            rows.forEach(r -> streak.put(r.instrumentId(), r.sbStreak()));
            rated += result.stream().filter(StockScore::rated).count();
            done++;
            if (done % 20 == 0 || done == tradingDays.size()) {
                ctx.progress("重放 " + done + "/" + tradingDays.size() + "（" + d + "）");
            }
        }
        // 中段重放：截止日之后已有的评分日，连续 STRONG_BUY 天数是按旧结果接的，现在可能对不上（2026-10-12 实测 TRV、WDC 两行）
        LocalDate last = tradingDays.getLast();
        scores.latestDate(SelectionModel.VERSION).filter(d -> d.isAfter(last)).ifPresent(d ->
                ctx.partial(last + " 之后还有评分（最晚 " + d + "），其连续 STRONG_BUY 天数没有跟着重算，把 to 延到 " + d + " 再重放一次"));
        return "量化选股重放 " + from + " ~ " + end + "：" + done + "/" + tradingDays.size() + " 个交易日，平均每天评级 "
                + (done == 0 ? 0 : rated / done) + " 只（模型 " + SelectionModel.VERSION + "）";
    }

    private void requireScorable(LocalDate date) {
        if (!days.isTradingDay(Market.US, date)) {
            throw new IllegalArgumentException(date + " 不是交易日");
        }
        if (date.isAfter(settledCutoff.get())) {
            throw new IllegalArgumentException(date + " 还没收盘落定（当前可评到 " + settledCutoff.get() + "）");
        }
    }

    List<SelectionScoreRepository.ScoreRow> rows(LocalDate date, List<StockInputs> inputs, List<StockScore> result,
                                                 Map<Long, Integer> prevStreak) {
        Map<Long, StockInputs> in = new HashMap<>();
        inputs.forEach(s -> in.put(s.instrumentId(), s));
        List<SelectionScoreRepository.ScoreRow> out = new ArrayList<>(result.size());
        for (StockScore s : result) {
            int streak = s.rating() == Rating.STRONG_BUY ? prevStreak.getOrDefault(s.instrumentId(), 0) + 1 : 0;
            out.add(new SelectionScoreRepository.ScoreRow(SelectionModel.VERSION, date, s.instrumentId(), s.sector(),
                    s.exclusion() == null ? null : s.exclusion().name(), s.exclusionDetail(),
                    dec(s.factorScores().get(Factor.VALUE)), dec(s.factorScores().get(Factor.GROWTH)),
                    dec(s.factorScores().get(Factor.PROFITABILITY)), dec(s.factorScores().get(Factor.MOMENTUM)),
                    dec(s.composite()), dec(s.compositePct()), s.rating() == null ? null : s.rating().name(), streak,
                    metricsJson(s), fingerprint(in.get(s.instrumentId()))));
        }
        return out;
    }

    /** {指标: [原值, 板块内百分位]}，按指标枚举顺序。 */
    String metricsJson(StockScore s) {
        Map<String, Object[]> m = new LinkedHashMap<>();
        for (Metric x : Metric.values()) {
            Double v = s.metrics().get(x);
            Double p = s.metricPct().get(x);
            if (v != null || p != null) {
                m.put(x.name(), new Object[] {v == null ? null : round(v, 6), p == null ? null : round(p, 2)});
            }
        }
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 输入指纹：板块、排除原因、各指标原值（10 位有效数字）的 SHA-256 前 32 位。 */
    static String fingerprint(StockInputs s) {
        StringBuilder b = new StringBuilder(SelectionModel.VERSION).append('|').append(s.sector()).append('|')
                .append(s.exclusion()).append('|');
        Map<Metric, Double> sorted = new EnumMap<>(Metric.class);
        sorted.putAll(s.metrics());
        sorted.values().removeIf(v -> v == null || !Double.isFinite(v));
        sorted.forEach((k, v) -> b.append(k).append('=').append(new BigDecimal(v).round(new java.math.MathContext(10))
                .stripTrailingZeros().toPlainString()).append(';'));
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String summary(LocalDate date, List<StockScore> result) {
        Map<String, Integer> byRating = new LinkedHashMap<>();
        Map<String, Integer> byExclusion = new LinkedHashMap<>();
        for (StockScore s : result) {
            if (s.rating() != null) {
                byRating.merge(s.rating().name(), 1, Integer::sum);
            } else if (s.exclusion() != null) {
                byExclusion.merge(s.exclusion().name(), 1, Integer::sum);
            } else {
                byExclusion.merge("UNRATED", 1, Integer::sum);
            }
        }
        return "量化选股评分 " + date + "：" + result.size() + " 只，评级 " + byRating + "，未评 " + byExclusion;
    }

    private static BigDecimal dec(Double v) {
        return v == null ? null : BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static double round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
