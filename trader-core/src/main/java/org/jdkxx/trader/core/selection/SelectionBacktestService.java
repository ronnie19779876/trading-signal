package org.jdkxx.trader.core.selection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.selection.AdjustedPrices;
import org.jdkxx.trader.domain.selection.Exclusion;
import org.jdkxx.trader.domain.selection.ModelSpec;
import org.jdkxx.trader.domain.selection.ModelSpecs;
import org.jdkxx.trader.domain.selection.Rating;
import org.jdkxx.trader.domain.selection.SelectionModel;
import org.jdkxx.trader.domain.selection.StockInputs;
import org.jdkxx.trader.domain.selection.StockScore;
import org.jdkxx.trader.domain.selection.backtest.BacktestStats;
import org.jdkxx.trader.domain.selection.backtest.ForwardReturns;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.selection.SelectionBacktestRunRepository;
import org.jdkxx.trader.storage.selection.SelectionScoreRepository;

/**
 * Quant selection historical test (ARCHITECTURE §30). Scores sample dates in memory under a
 * pre-registered {@link ModelSpec}, measures forward returns and writes one report per run to
 * {@code selection_backtest_run}. Never writes {@code selection_score}.
 *
 * <p>Discipline (§30.6): a range reaching the holdout (2023-01-01 onwards) needs {@code final=true}
 * and is counted as a holdout use; a training run drops every sample whose exit falls in the
 * holdout, so training numbers never see holdout prices.
 */
public class SelectionBacktestService {

  static final LocalDate HOLDOUT_START = LocalDate.of(2023, 1, 1);

  /** First day of the F1 replay (12-month momentum needs 2017 prices) */
  static final LocalDate EARLIEST = LocalDate.of(2018, 1, 2);

  static final int[] HORIZONS = {21, 63, 126, 252};

  /** Primary horizon of the criteria (§30.8) */
  static final int PRIMARY = 63;

  /** Start offsets as fractions of h for the robustness rows (§30.4) */
  static final int OFFSET_PARTS = 4;

  /** Universe modes (§30.5). */
  enum Universe {
    ALL,
    PIT
  }

  private final SelectionDataLoader loader;
  private final SelectionScoreRepository scores;
  private final SelectionBacktestRunRepository runs;
  private final IndexConstituentRepository constituents;
  private final TradingDayRepository days;
  private final Supplier<LocalDate> settledCutoff;
  private final ObjectMapper json;

  public SelectionBacktestService(
      SelectionDataLoader loader,
      SelectionScoreRepository scores,
      SelectionBacktestRunRepository runs,
      IndexConstituentRepository constituents,
      TradingDayRepository days,
      Supplier<LocalDate> settledCutoff,
      ObjectMapper json) {
    this.loader = loader;
    this.scores = scores;
    this.runs = runs;
    this.constituents = constituents;
    this.days = days;
    this.settledCutoff = settledCutoff;
    this.json = json;
  }

  /** Checks a request before it is queued; throws IllegalArgumentException with the reason. */
  ModelSpec validate(String specName, LocalDate from, LocalDate to, boolean finalRun) {
    ModelSpec spec =
        ModelSpecs.find(specName)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "没有预登记的规格 "
                            + specName
                            + "（可选："
                            + ModelSpecs.all().stream().map(ModelSpec::version).toList()
                            + "）"));
    if (from.isBefore(EARLIEST)) {
      throw new IllegalArgumentException("from 不能早于 " + EARLIEST + "（12 个月动能要 2017 年起的价格）");
    }
    if (from.isAfter(to)) {
      throw new IllegalArgumentException("from 不能晚于 to");
    }
    if (to.isAfter(settledCutoff.get())) {
      throw new IllegalArgumentException("to 不能晚于已收盘落定的 " + settledCutoff.get());
    }
    if (!finalRun && !to.isBefore(HOLDOUT_START)) {
      throw new IllegalArgumentException(
          "区间触及留出段（" + HOLDOUT_START + " 起）：只有最终评估才动用留出段，须 final=true，并会留痕计数");
    }
    if (finalRun && to.isBefore(HOLDOUT_START)) {
      throw new IllegalArgumentException("final=true 但区间不触及留出段（" + HOLDOUT_START + " 起），用训练段运行即可");
    }
    return spec;
  }

  public String run(
      String specName, LocalDate from, LocalDate to, boolean finalRun, JobContext ctx) {
    ModelSpec spec = validate(specName, from, to, finalRun);
    LocalDate cutoff = settledCutoff.get();
    ctx.progress("加载输入 " + from + " ~ " + to + "（价格到 " + cutoff + "）");
    SelectionDataset data = loader.load(from, to, cutoff);
    Map<Long, AdjustedPrices> prices = new HashMap<>();
    data.stocks().forEach(s -> prices.put(s.row().id(), s.prices()));
    Map<Long, LocalDate> addedOn = constituents.addedOn();
    // Windows end at the last day that actually has bars: the calendar can run ahead of the bars
    // (the dev database stops collecting), and windows past them would only count as attrition
    LocalDate end = lastBar(prices.values(), cutoff);
    LocalDate exitBefore = finalRun ? null : HOLDOUT_START;
    List<LocalDate> calendar = days.between(Market.US, LocalDate.of(from.getYear(), 1, 1), end);
    List<LocalDate> sampleDays =
        calendar.stream().filter(d -> !d.isBefore(from) && !d.isAfter(to)).toList();
    if (sampleDays.isEmpty()) {
      throw new IllegalArgumentException(from + " ~ " + to + " 之间没有交易日");
    }

    // Which (horizon, offset) grids each date belongs to; only dates with a valid window
    Map<LocalDate, List<int[]>> membership = new TreeMap<>();
    for (int h : HORIZONS) {
      for (int part = 0; part < OFFSET_PARTS; part++) {
        int offset = h * part / OFFSET_PARTS;
        for (int i = offset; i < sampleDays.size(); i += h) {
          LocalDate t = sampleDays.get(i);
          if (ForwardReturns.window(calendar, t, h, exitBefore).isPresent()) {
            membership.computeIfAbsent(t, k -> new ArrayList<>()).add(new int[] {h, part});
          }
        }
      }
    }

    // cells.get(universe).get(h).get(part).get(total) -> samples
    Map<String, List<BacktestStats.DateSample>> cells = new HashMap<>();
    Map<String, Double> baselines = new HashMap<>();
    F1Check check = spec.version().equals(SelectionModel.VERSION) ? new F1Check() : null;
    int done = 0;
    for (Map.Entry<LocalDate, List<int[]>> e : membership.entrySet()) {
      if (ctx.cancelled()) {
        throw new IllegalStateException("已取消，停在 " + e.getKey());
      }
      LocalDate t = e.getKey();
      List<StockInputs> in = data.inputsOn(t, spec);
      Map<Universe, List<StockScore>> scored = new EnumMap<>(Universe.class);
      scored.put(Universe.ALL, SelectionModel.score(in, spec));
      scored.put(Universe.PIT, SelectionModel.score(pointInTime(in, addedOn, t), spec));
      if (check != null) {
        check.compare(t, in);
      }
      for (int[] grid : e.getValue()) {
        int h = grid[0];
        int part = grid[1];
        ForwardReturns.Window w = ForwardReturns.window(calendar, t, h, exitBefore).orElseThrow();
        for (Universe u : Universe.values()) {
          for (boolean total : new boolean[] {true, false}) {
            boolean full = part == 0 && total;
            cells
                .computeIfAbsent(key(u, h, part, total), k -> new ArrayList<>())
                .add(
                    sample(
                        t,
                        w,
                        h,
                        total,
                        full,
                        scored.get(u),
                        prices,
                        calendar,
                        spec.staleDays(),
                        exitBefore,
                        baselines));
          }
        }
      }
      done++;
      if (done % 20 == 0 || done == membership.size()) {
        ctx.progress("检验 " + done + "/" + membership.size() + "（" + t + "）");
      }
    }

    Map<String, Object> report = report(spec, from, to, finalRun, end, cells, addedOn, data, check);
    String body;
    String specJson;
    try {
      body = json.writeValueAsString(report);
      specJson = json.writeValueAsString(spec);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
    long id =
        runs.insert(spec.version(), spec.candidate(), specJson, from, to, finalRun, ctx.id(), body);
    if (check != null && check.mismatches() > 0) {
      ctx.partial("F1 内存重算与库内评分有 " + check.mismatches() + " 行不一致，见报告 f1Check");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> verdict = (Map<String, Object>) report.get("criteria");
    return "量化选股历史检验 #"
        + id
        + "：规格 "
        + spec.version()
        + "，"
        + from
        + " ~ "
        + to
        + (finalRun ? "（留出段，第 " + report.get("holdoutUse") + " 次）" : "（训练段）")
        + "，采样日 "
        + membership.size()
        + "；判据 "
        + verdict.get("allMet");
  }

  /** Latest bar date over all series, capped at the cutoff. */
  static LocalDate lastBar(java.util.Collection<AdjustedPrices> prices, LocalDate cutoff) {
    LocalDate last = null;
    for (AdjustedPrices p : prices) {
      if (p != null && p.valid() && p.size() > 0) {
        LocalDate d = p.date(p.size() - 1);
        if (last == null || d.isAfter(last)) {
          last = d;
        }
      }
    }
    return last == null || last.isAfter(cutoff) ? cutoff : last;
  }

  /**
   * Inputs for the point-in-time universe: names not yet in an index on t, or without any known
   * date added, are excluded before scoring, so sector groups and percentiles are rebuilt without
   * them.
   */
  static List<StockInputs> pointInTime(
      List<StockInputs> in, Map<Long, LocalDate> addedOn, LocalDate t) {
    List<StockInputs> out = new ArrayList<>(in.size());
    for (StockInputs s : in) {
      LocalDate added = addedOn.get(s.instrumentId());
      if (s.exclusion() == null && (added == null || added.isAfter(t))) {
        out.add(
            new StockInputs(
                s.instrumentId(),
                s.symbol(),
                s.sector(),
                Exclusion.NOT_INDEX_MEMBER,
                added == null ? "没有指数加入日期" : "加入指数日期 " + added,
                s.metrics(),
                s.applicable()));
      } else {
        out.add(s);
      }
    }
    return out;
  }

  /** Observations of one sample: rated names, their forward return and same-name baseline. */
  static BacktestStats.DateSample sample(
      LocalDate t,
      ForwardReturns.Window w,
      int h,
      boolean total,
      boolean full,
      List<StockScore> scored,
      Map<Long, AdjustedPrices> prices,
      List<LocalDate> calendar,
      int staleDays,
      LocalDate exitBefore,
      Map<String, Double> baselines) {
    List<BacktestStats.Obs> obs = new ArrayList<>();
    Map<Rating, Integer> attrition = new EnumMap<>(Rating.class);
    for (StockScore s : scored) {
      if (s.rating() == null) {
        continue;
      }
      AdjustedPrices p = prices.get(s.instrumentId());
      double r = ForwardReturns.of(p, w, total, staleDays);
      if (!Double.isFinite(r)) {
        attrition.merge(s.rating(), 1, Integer::sum);
        continue;
      }
      double base = Double.NaN;
      if (full) {
        int year = w.entry().getYear();
        base =
            baselines.computeIfAbsent(
                s.instrumentId() + "|" + year + "|" + h + "|" + total,
                k ->
                    ForwardReturns.nameYearBaseline(
                        p, calendar, year, h, total, staleDays, exitBefore));
      }
      obs.add(
          new BacktestStats.Obs(
              s.instrumentId(),
              s.sector(),
              s.rating(),
              s.composite(),
              full ? s.factorScores() : Map.of(),
              full ? s.metricPct() : Map.of(),
              r,
              base));
    }
    return new BacktestStats.DateSample(t, obs, attrition);
  }

  private Map<String, Object> report(
      ModelSpec spec,
      LocalDate from,
      LocalDate to,
      boolean finalRun,
      LocalDate end,
      Map<String, List<BacktestStats.DateSample>> cells,
      Map<Long, LocalDate> addedOn,
      SelectionDataset data,
      F1Check check) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("spec", spec);
    out.put("generatedAt", Instant.now());
    Map<String, Object> sample = new LinkedHashMap<>();
    sample.put("from", from);
    sample.put("to", to);
    sample.put("holdout", finalRun);
    sample.put("holdoutStart", HOLDOUT_START);
    sample.put("exitBefore", finalRun ? null : HOLDOUT_START);
    sample.put("pricesThrough", end);
    sample.put("entry", "评分日之后第一个交易日收盘入场，再过 h 个交易日收盘出场");
    out.put("sample", sample);
    out.put("holdoutUse", finalRun ? runs.holdoutUses() + 1 : null);
    List<String> tried = new ArrayList<>(runs.candidatesTried());
    if (!finalRun && spec.candidate() && !tried.contains(spec.version())) {
      tried.add(spec.version());
    }
    out.put("candidatesTried", tried);
    long withDate = data.stocks().stream().filter(s -> addedOn.containsKey(s.row().id())).count();
    out.put(
        "universe",
        Map.of(
            "targets",
            data.stocks().size(),
            "withIndexAddedDate",
            withDate,
            "pitNote",
            "PIT 口径只纳入加入指数日期 ≤ 采样日的标的；没有加入日期的（池与持仓里的非成分股、来源没给日期的）排除"));

    Map<String, Object> full = new LinkedHashMap<>();
    Map<String, Object> priceOnly = new LinkedHashMap<>();
    Map<String, Object> offsets = new LinkedHashMap<>();
    for (Universe u : Universe.values()) {
      Map<String, Object> f = new LinkedHashMap<>();
      Map<String, Object> p = new LinkedHashMap<>();
      Map<String, Object> o = new LinkedHashMap<>();
      for (int h : HORIZONS) {
        f.put(
            String.valueOf(h),
            BacktestStats.report(cells.getOrDefault(key(u, h, 0, true), List.of())));
        p.put(
            String.valueOf(h),
            BacktestStats.headline(cells.getOrDefault(key(u, h, 0, false), List.of())));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int part = 0; part < OFFSET_PARTS; part++) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("offset", h * part / OFFSET_PARTS);
          row.put(
              "headline",
              BacktestStats.headline(cells.getOrDefault(key(u, h, part, true), List.of())));
          rows.add(row);
        }
        o.put(String.valueOf(h), rows);
      }
      full.put(u.name(), f);
      priceOnly.put(u.name(), p);
      offsets.put(u.name(), o);
    }
    out.put("criteria", criteria(full));
    out.put("totalReturn", full);
    out.put("priceOnly", priceOnly);
    out.put("offsets", offsets);
    out.put("f1Check", check == null ? null : check.result());
    out.put(
        "knownBiases",
        List.of(
            "幸存者偏差：范围是今天的成分股，被删出指数的标的拿不到价格；PIT 口径只去掉「后来才加入」的那部分",
            "财报重述：成长与盈利能力用的是今天的数（§25.8）",
            "板块用当前 GICS 分类，2023 年改分类不回溯",
            "富途 PE 历史与申报值反推有约 5% 的系统差",
            "分拆（spin-off）不补偿，价格照实下跳",
            "不计佣金、滑点；同标的同年对照是下限（长期有效的截面差异也被减掉）"));
    return out;
  }

  /**
   * §30.8 on the primary cell (h = 63, sector excess, total return): evaluated on every run, but
   * only a holdout run's verdict counts.
   */
  static Map<String, Object> criteria(Map<String, Object> full) {
    BacktestStats.Report all = primary(full, Universe.ALL);
    BacktestStats.Report pit = primary(full, Universe.PIT);
    boolean c1 = icOk(all);
    boolean c2 = sbOk(all);
    double nameYear = sb(all, BacktestStats.Excess.NAME_YEAR);
    boolean c3 = icOk(pit) && sbOk(pit) && Double.isFinite(nameYear) && nameYear >= 0;
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("horizon", PRIMARY);
    out.put("compositeIcPositiveT2", c1);
    out.put("sbPositiveAndAboveSs", c2);
    out.put("holdsUnderPitAndNameYear", c3);
    out.put("allMet", c1 && c2 && c3);
    out.put("note", "判据只在留出段（final=true）的运行上算数；训练段的这几项只供参考");
    return out;
  }

  private static BacktestStats.Report primary(Map<String, Object> full, Universe u) {
    @SuppressWarnings("unchecked")
    Map<String, Object> byH = (Map<String, Object>) full.get(u.name());
    return (BacktestStats.Report) byH.get(String.valueOf(PRIMARY));
  }

  private static boolean icOk(BacktestStats.Report r) {
    return r.compositeIc().mean() > 0 && r.compositeIc().t() >= 2;
  }

  private static boolean sbOk(BacktestStats.Report r) {
    double sb = sb(r, BacktestStats.Excess.SECTOR);
    BacktestStats.Summary ss =
        r.ratingExcess()
            .getOrDefault(BacktestStats.Excess.SECTOR, Map.of())
            .get(Rating.STRONG_SELL);
    return Double.isFinite(sb) && sb > 0 && ss != null && sb > ss.mean();
  }

  private static double sb(BacktestStats.Report r, BacktestStats.Excess e) {
    BacktestStats.Summary s = r.ratingExcess().getOrDefault(e, Map.of()).get(Rating.STRONG_BUY);
    return s == null ? Double.NaN : s.mean();
  }

  private static String key(Universe u, int h, int part, boolean total) {
    return u + "|" + h + "|" + part + "|" + total;
  }

  /**
   * Guard that the {@link ModelSpec} refactor left F1 unchanged (§30.10). Input fingerprints are
   * compared for every instrument present both today and in the stored day. Ratings and composites
   * (2 decimals) are compared after re-scoring in memory with F1 on <b>exactly the instruments
   * stored for that day</b>. The universe is matched first because names added since the replay (or
   * removed from it) take part in the sector percentiles and shift everybody else's composite; that
   * is a universe change, not a model change, and is reported separately. A date whose stored names
   * are not all in today's universe cannot be reproduced and is skipped (counted).
   */
  final class F1Check {
    private int dates;
    private int datesNotStored;
    private int datesUniverseGone;
    private int rows;
    private int fingerprintRows;
    private int onlyInUniverse;
    private int fingerprint;
    private int rating;
    private int composite;
    private final List<String> examples = new ArrayList<>();

    void compare(LocalDate t, List<StockInputs> inputs) {
      List<SelectionScoreRepository.ScoreView> stored =
          scores.day(SelectionModel.VERSION, t, null, null);
      if (stored.isEmpty()) {
        datesNotStored++;
        return;
      }
      Map<Long, SelectionScoreRepository.ScoreView> byId = new HashMap<>();
      stored.forEach(v -> byId.put(v.instrumentId(), v));
      List<StockInputs> same = new ArrayList<>();
      for (StockInputs s : inputs) {
        SelectionScoreRepository.ScoreView v = byId.get(s.instrumentId());
        if (v == null) {
          onlyInUniverse++;
          continue;
        }
        same.add(s);
        // The fingerprint is per instrument and does not depend on the universe: always compared
        fingerprintRows++;
        if (!SelectionScoringService.fingerprint(s).equals(v.fingerprint())) {
          fingerprint++;
          example(t, s.symbol(), "输入指纹不同");
        }
      }
      if (same.size() != byId.size()) {
        datesUniverseGone++;
        example(t, "-", "库内有 " + (byId.size() - same.size()) + " 只今天已不在范围内，这天无法复现，跳过");
        return;
      }
      dates++;
      for (StockScore s : SelectionModel.score(same, ModelSpec.F1)) {
        rows++;
        SelectionScoreRepository.ScoreView v = byId.get(s.instrumentId());
        String r = s.rating() == null ? null : s.rating().name();
        if (!Objects.equals(r, v.rating())) {
          rating++;
          example(t, s.symbol(), "评级 " + v.rating() + " → " + r);
        }
        BigDecimal c =
            s.composite() == null
                ? null
                : BigDecimal.valueOf(s.composite()).setScale(2, RoundingMode.HALF_UP);
        if (c == null
            ? v.composite() != null
            : v.composite() == null || c.compareTo(v.composite()) != 0) {
          composite++;
          example(t, s.symbol(), "合成分 " + v.composite() + " → " + c);
        }
      }
    }

    private void example(LocalDate t, String symbol, String what) {
      if (examples.size() < 20) {
        examples.add(t + " " + symbol + "：" + what);
      }
    }

    int mismatches() {
      return fingerprint + rating + composite;
    }

    Map<String, Object> result() {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("datesCompared", dates);
      out.put("datesNotStored", datesNotStored);
      out.put("datesUniverseGone", datesUniverseGone);
      out.put("fingerprintRows", fingerprintRows);
      out.put("fingerprintDiff", fingerprint);
      out.put("rows", rows);
      out.put("ratingDiff", rating);
      out.put("compositeDiff", composite);
      out.put("onlyInTodaysUniverse", onlyInUniverse);
      out.put("note", "按库内当天的标的集合重算后比对；onlyInTodaysUniverse 是重放之后才进范围的标的行数，它们改变的是范围、不是模型");
      out.put("examples", examples);
      return out;
    }
  }

  /** Spec list with usage counts, for {@code GET /api/selection/backtest/specs}. */
  public List<Map<String, Object>> specs() {
    Map<String, int[]> usage = new HashMap<>();
    for (Object[] u : runs.usage()) {
      usage.put((String) u[0], new int[] {(Integer) u[1], (Integer) u[2]});
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (ModelSpec s : ModelSpecs.all()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("spec", s);
      int[] n = usage.getOrDefault(s.version(), new int[2]);
      m.put("trainingRuns", n[0]);
      m.put("holdoutRuns", n[1]);
      out.add(m);
    }
    return out;
  }

  public List<SelectionBacktestRunRepository.RunSummary> list(int limit) {
    return runs.list(limit);
  }

  public Optional<SelectionBacktestRunRepository.Run> find(long id) {
    return runs.find(id);
  }
}
