package org.jdkxx.trader.core.account;

import org.jdkxx.trader.core.marketdata.audit.BarAuditService.Check;
import org.jdkxx.trader.core.signal.ai.EarningsCalendarHint;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.domain.holding.EarningsReaction;
import org.jdkxx.trader.domain.holding.HoldingRules;
import org.jdkxx.trader.domain.holding.TrendReview;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.signal.SentinelThresholds;
import org.jdkxx.trader.domain.signal.SignalBar;
import org.jdkxx.trader.domain.signal.StructuralAdjustment;
import org.jdkxx.trader.storage.account.AccountSnapshotRepository;
import org.jdkxx.trader.storage.account.AccountSnapshotRow;
import org.jdkxx.trader.storage.account.PositionSnapshotRow;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.PoolRepository;
import org.jdkxx.trader.storage.marketdata.PoolRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

/**
 * 持仓风险面板（3.5.0，ARCHITECTURE §29）：财报预警、趋势状态、加仓检查、减仓复核条件。只读，只查库、不访问网关。
 *
 * <p><b>只检查用户定的规则、给出数字，不给买卖建议。</b>输出的措辞是「满足 / 不满足你设定的加仓条件」「触发复核条件」。
 * 持仓与净值取最近一份账户快照（每日美东 18:00）；趋势与财报反应用结构口径日 K（跨拆股连续），判定日是每只标的最新一根 K 线。
 */
public class HoldingReviewService {

    /** 公告日只有最近约 20 季（5 年），多取一年给 200 日均线预热 */
    static final int HISTORY_YEARS = 6;
    /** 52 周高点 */
    static final int YEAR_BARS = 252;
    /** 下一次财报没排期时按季度节奏估计 */
    static final int QUARTER_DAYS = 91;
    /** 加仓检查结论里的规则名 */
    static final java.util.Map<String, String> RULE_NAME = java.util.Map.of("TREND", "趋势门", "EARNINGS_WINDOW", "财报窗口",
            "LOSS_BUDGET", "风险预算", "NO_AVERAGING_DOWN", "不逆势摊低");

    private final HoldingReviewProperties props;
    private final HoldingRules rules;
    private final AccountSnapshotRepository snapshots;
    private final PoolRepository pool;
    private final InstrumentRepository instruments;
    private final DailyBarRepository bars;
    private final RehabFactorRepository rehabs;
    private final TradingDayRepository days;
    private final SelectionEarningsRepository earnings;
    private final Clock clock;
    private final ZoneId zone;

    public HoldingReviewService(HoldingReviewProperties props, AccountSnapshotRepository snapshots, PoolRepository pool,
                                InstrumentRepository instruments, DailyBarRepository bars, RehabFactorRepository rehabs,
                                TradingDayRepository days, SelectionEarningsRepository earnings, Clock clock, ZoneId zone) {
        this.props = props;
        this.rules = HoldingRules.of(SentinelThresholds.V1, props.smaShort(), props.breakConfirmDays(), props.deathCross());
        this.snapshots = snapshots;
        this.pool = pool;
        this.instruments = instruments;
        this.bars = bars;
        this.rehabs = rehabs;
        this.days = days;
        this.earnings = earnings;
        this.clock = clock;
        this.zone = zone;
    }

    // ------------------------------------------------------------------ 返回结构（比例一律是百分数的数值，-9.63 表示 -9.63%）

    public record Params(int windowDays, double stressMultiple, BigDecimal lossBudgetPct, HoldingRules rules) {
    }

    public record Review(LocalDate snapshotDate, BigDecimal netLiquidation, Params params, List<Item> items) {
    }

    /**
     * @param role                 HOLDING（快照里的持仓）或 POOL（池里没持有的）
     * @param maxWeightPctInBudget 按亏损预算换算：过财报时这只最多占净值的百分比（没设预算或没有反应样本为 null）
     * @param problem              这只算不出来的原因（没有日 K、口径换算失败）
     */
    public record Item(String symbol, String role, BigDecimal quantity, BigDecimal averageCost, BigDecimal price,
                       BigDecimal marketValue, Double weightPct, Trend trend, Earnings earnings, List<Flag> triggers,
                       Double maxWeightPctInBudget, String problem) {
    }

    /**
     * @param deathCrossSince 当前这段短均线在长均线之下是从哪天开始的
     * @param pnlVsCostPct    最新收盘相对盈透均价（只有持仓有）
     */
    public record Trend(LocalDate date, double close, Double smaShort, Double smaLong, Double smaLongPrior, String state,
                        String stateLabel, boolean trendGatePass, Double pctVsSmaShort, Double pctVsSmaLong, int belowDays,
                        boolean deathCross, LocalDate deathCrossSince, Double chandelier, boolean chandelierBreach,
                        Double pctFrom52wHigh, Double pnlVsCostPct) {
    }

    /**
     * @param tradingDaysAhead 最新 K 线那天之后第几个交易日（0 = 最新 K 线当天盘后或时段未知）
     * @param estimated        券商还没排期，按上次公告 + 91 天估计
     */
    public record Upcoming(String period, LocalDate date, String timing, int tradingDaysAhead, boolean estimated, boolean inWindow) {
    }

    public record SampleView(String period, LocalDate pubDate, String timing, LocalDate reactionDate, Double gapPct, double movePct) {
    }

    public record StatsView(int samples, double meanAbsMovePct, double medianAbsMovePct, double maxDropPct, double maxRisePct,
                            int bigMoves, int ups, int downs, Double worstGapPct) {
    }

    /**
     * @param historicalDropPct 历史最大跌幅（正数）
     * @param stressDropPct     压力情景跌幅 = 历史最大跌幅 × 压力倍数
     * @param historicalLoss    持仓市值 × 历史最大跌幅（池成员没有）
     * @param stressLoss        持仓市值 × 压力跌幅
     * @param stressLossPctOfNav 压力情景亏损占净值
     * @param overBudget        超出亏损预算（没设预算为 null）
     */
    public record Scenario(double historicalDropPct, double stressDropPct, Double historicalLoss, Double stressLoss,
                           Double stressLossPctOfNav, Boolean overBudget) {
    }

    public record Earnings(Upcoming next, StatsView stats, List<SampleView> samples, Scenario scenario) {
    }

    /** 触发的复核条件：BREAK_CONFIRMED / DEATH_CROSS / CHANDELIER_BREACH / EARNINGS_OVER_BUDGET。 */
    public record Flag(String trigger, String detail) {
    }

    /** 加仓检查的一条：PASS / FAIL / SKIPPED（条件无法判定，比如没设预算）。 */
    public record Rule(String rule, String status, String detail) {
    }

    public record AddCheck(String symbol, double shares, double price, LocalDate priceDate, boolean held, boolean allPass,
                           String conclusion, List<Rule> rules) {
    }

    public record HistoryDay(LocalDate date, double close, String state, Set<TrendReview.Trigger> active, Set<TrendReview.Trigger> edges) {
    }

    // ------------------------------------------------------------------ 面板

    public Review review(Integer windowDays) {
        int window = windowDays == null ? props.windowDays() : windowDays;
        if (window < 1 || window > 60) {
            throw new IllegalArgumentException("days 取 1~60");
        }
        LocalDate today = LocalDate.now(clock.withZone(zone));
        TreeSet<LocalDate> calendar = calendar(today);
        AccountSnapshotRow snap = snapshots.latest().orElse(null);
        BigDecimal nav = snap == null ? null : snap.netLiquidation();
        List<Item> items = new ArrayList<>();
        Set<Long> held = new HashSet<>();
        if (snap != null) {
            for (PositionSnapshotRow p : snapshots.positions(snap.id())) {
                if (p.cashEquivalent() || p.instrumentId() == null) {
                    continue;
                }
                InstrumentRow row = instruments.findById(p.instrumentId()).orElse(null);
                if (row == null) {
                    continue;
                }
                held.add(row.id());
                items.add(item(row, PoolRole.HOLDING, p, nav, window, today, calendar));
            }
        }
        if (props.includePool()) {
            for (PoolRow pr : pool.findAll()) {
                if (pr.role() != PoolRole.POOL || held.contains(pr.instrumentId())) {
                    continue;
                }
                instruments.findById(pr.instrumentId()).ifPresent(row -> items.add(item(row, PoolRole.POOL, null, nav, window, today, calendar)));
            }
        }
        return new Review(snap == null ? null : snap.asOfDate(), nav, params(window), items);
    }

    private Params params(int window) {
        return new Params(window, props.stressMultiple(), props.lossBudgetPct(), rules);
    }

    private Item item(InstrumentRow row, PoolRole role, PositionSnapshotRow p, BigDecimal nav, int window, LocalDate today,
                      TreeSet<LocalDate> calendar) {
        Computed c;
        try {
            c = compute(row, today, calendar);
        } catch (RuntimeException e) {
            return new Item(row.symbol(), role.name(), p == null ? null : p.quantity(), p == null ? null : p.averageCost(),
                    p == null ? null : p.price(), p == null ? null : p.marketValue(), null, null, null, List.of(), null, e.getMessage());
        }
        TrendReview.Day last = c.days().getLast();
        Trend trend = trend(c, p);
        Upcoming next = upcoming(c, window);
        EarningsReaction.Stats stats = EarningsReaction.stats(c.samples());
        BigDecimal mv = p == null ? null : p.marketValue();
        Scenario scenario = scenario(stats, mv, nav);
        Double weight = mv == null || nav == null || nav.signum() <= 0 ? null : pct(mv.doubleValue() / nav.doubleValue());
        Double maxWeight = props.lossBudgetPct() == null || stats == null || stats.maxDrop() <= 0 ? null
                : round2(props.lossBudgetPct().doubleValue() / (stats.maxDrop() * props.stressMultiple()));
        List<Flag> flags = new ArrayList<>();
        if (role == PoolRole.HOLDING) {
            flags.addAll(priceFlags(last, c));
            if (next != null && next.inWindow() && scenario != null && Boolean.TRUE.equals(scenario.overBudget())) {
                flags.add(new Flag("EARNINGS_OVER_BUDGET", next.date() + timingText(next.timing()) + "发财报（第 " + next.tradingDaysAhead()
                        + " 个交易日）" + (next.estimated() ? "（估计日期）" : "") + "，压力情景亏损 " + money(scenario.stressLoss())
                        + "（净值 " + scenario.stressLossPctOfNav() + "%）超出预算 " + props.lossBudgetPct() + "%"));
            }
        }
        return new Item(row.symbol(), role.name(), p == null ? null : p.quantity(), p == null ? null : p.averageCost(),
                p == null ? BigDecimal.valueOf(last.close()) : p.price(), mv, weight, trend,
                new Earnings(next, statsView(stats), c.samples().stream().map(HoldingReviewService::sampleView).toList(), scenario),
                flags, maxWeight, null);
    }

    private List<Flag> priceFlags(TrendReview.Day d, Computed c) {
        List<Flag> out = new ArrayList<>();
        if (d.active().contains(TrendReview.Trigger.BREAK_CONFIRMED)) {
            out.add(new Flag("BREAK_CONFIRMED", "连续 " + d.belowDays() + " 天收盘低于 " + rules.smaLong() + " 日均线（收盘 "
                    + num(d.close()) + "，均线 " + num(d.smaLong()) + "，" + signedPct(d.close() / d.smaLong() - 1) + "）"));
        }
        if (d.active().contains(TrendReview.Trigger.DEATH_CROSS)) {
            out.add(new Flag("DEATH_CROSS", rules.smaShort() + " 日均线 " + num(d.smaShort()) + " 在 " + rules.smaLong() + " 日均线 "
                    + num(d.smaLong()) + " 之下（自 " + deathCrossSince(c.days()) + "）"));
        }
        if (d.active().contains(TrendReview.Trigger.CHANDELIER_BREACH)) {
            out.add(new Flag("CHANDELIER_BREACH", "收盘 " + num(d.close()) + " 低于吊灯线 " + num(d.chandelier()) + "（最近 "
                    + rules.chandelierPeriod() + " 根最高价 − " + rules.chandelierAtrMultiple() + " × ATR" + rules.atrPeriod() + "）"));
        }
        return out;
    }

    // ------------------------------------------------------------------ 加仓检查

    public AddCheck addCheck(String symbol, double shares) {
        if (!(shares > 0)) {
            throw new IllegalArgumentException("shares 必须大于 0");
        }
        String s = symbol.trim().toUpperCase(Locale.ROOT).replace(' ', '.');
        InstrumentRow row = instruments.find(Instrument.us(s)).orElseThrow(() -> new NoSuchElementException("没有标的 " + s));
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Computed c = compute(row, today, calendar(today));
        TrendReview.Day last = c.days().getLast();
        AccountSnapshotRow snap = snapshots.latest().orElse(null);
        PositionSnapshotRow p = snap == null ? null : snapshots.positions(snap.id()).stream()
                .filter(x -> x.instrumentId() != null && x.instrumentId() == row.id()).findFirst().orElse(null);
        BigDecimal nav = snap == null ? null : snap.netLiquidation();
        EarningsReaction.Stats stats = EarningsReaction.stats(c.samples());
        Upcoming next = upcoming(c, props.windowDays());
        List<Rule> list = new ArrayList<>();

        boolean gate = gatePass(last);
        list.add(new Rule("TREND", last.state() == TrendReview.State.UNAVAILABLE ? "SKIPPED" : gate ? "PASS" : "FAIL",
                last.state() == TrendReview.State.UNAVAILABLE ? "样本不足，无法判定趋势"
                        : "收盘 " + num(last.close()) + (last.close() > last.smaLong() ? " > " : " ≤ ") + rules.smaLong() + " 日均线 "
                        + num(last.smaLong()) + "；均线" + (last.smaLong() > last.smaLongPrior() ? "上升" : "未上升") + "（" + rules.slopeLookback()
                        + " 天前 " + num(last.smaLongPrior()) + "）——" + last.state().label()));

        if (next == null) {
            list.add(new Rule("EARNINGS_WINDOW", "PASS", row.type() == SecurityType.STOCK ? "没有财报公告日记录" : "不是普通股，没有财报"));
        } else {
            list.add(new Rule("EARNINGS_WINDOW", next.inWindow() ? "FAIL" : "PASS", "下一次财报 " + next.date() + timingText(next.timing())
                    + (next.estimated() ? "（估计日期）" : "") + "，在第 " + next.tradingDaysAhead() + " 个交易日；预警窗口 "
                    + props.windowDays() + " 个交易日"));
        }

        double heldQty = p == null ? 0 : p.quantity().doubleValue();
        double after = (heldQty + shares) * last.close();
        if (props.lossBudgetPct() == null) {
            list.add(new Rule("LOSS_BUDGET", "SKIPPED", "没有设定亏损预算（trader.holding-review.loss-budget-pct），不判定；加仓后市值约 " + money(after)));
        } else if (stats == null || stats.maxDrop() <= 0) {
            list.add(new Rule("LOSS_BUDGET", "SKIPPED", "没有财报后下跌的历史样本，无法估计压力情景"));
        } else if (nav == null || nav.signum() <= 0) {
            list.add(new Rule("LOSS_BUDGET", "SKIPPED", "没有账户快照，不知道净值"));
        } else {
            double stress = stats.maxDrop() * props.stressMultiple();
            double loss = after * stress;
            double budget = nav.doubleValue() * props.lossBudgetPct().doubleValue() / 100;
            list.add(new Rule("LOSS_BUDGET", loss <= budget ? "PASS" : "FAIL", "加仓后市值约 " + money(after) + "，压力情景（历史最大跌幅 "
                    + round2(stats.maxDrop() * 100) + "% × " + props.stressMultiple() + "）亏损约 " + money(loss) + "，预算 "
                    + props.lossBudgetPct() + "% 净值 = " + money(budget)));
        }

        if (p == null) {
            list.add(new Rule("NO_AVERAGING_DOWN", "PASS", "目前没有持有"));
        } else {
            double cost = p.averageCost() == null ? Double.NaN : p.averageCost().doubleValue();
            boolean averagingDown = !Double.isNaN(cost) && last.close() < cost && !gate;
            list.add(new Rule("NO_AVERAGING_DOWN", averagingDown ? "FAIL" : "PASS", "现价 " + num(last.close()) + "，你的均价 " + num(cost)
                    + (averagingDown ? "，低于均价且趋势未通过：属于逆势摊低" : "")));
        }

        List<String> failed = list.stream().filter(r -> "FAIL".equals(r.status())).map(r -> RULE_NAME.get(r.rule())).toList();
        String conclusion = failed.isEmpty() ? "满足你设定的加仓条件" + (list.stream().anyMatch(r -> "SKIPPED".equals(r.status())) ? "（有条件未判定）" : "")
                : "不满足你设定的加仓条件：" + String.join("、", failed);
        return new AddCheck(row.symbol(), shares, last.close(), last.date(), p != null, failed.isEmpty(), conclusion, list);
    }

    // ------------------------------------------------------------------ 逐日历史（历史验证用）

    public List<HistoryDay> history(String symbol, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to 早于 from");
        }
        String s = symbol.trim().toUpperCase(Locale.ROOT).replace(' ', '.');
        InstrumentRow row = instruments.find(Instrument.us(s)).orElseThrow(() -> new NoSuchElementException("没有标的 " + s));
        List<SignalBar> series = structural(row, from.minusDays(450), to,
                new TreeSet<>(days.between(Market.US, from.minusDays(450), to)));
        List<HistoryDay> out = new ArrayList<>();
        for (TrendReview.Day d : TrendReview.evaluate(series, rules)) {
            if (!d.date().isBefore(from) && !d.date().isAfter(to)) {
                out.add(new HistoryDay(d.date(), d.close(), d.state().name(), d.active(), d.edges()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 审计提示（收盘巡检第三段，只提示）

    public List<Check> auditChecks() {
        Review r = review(null);
        List<Item> holdings = r.items().stream().filter(i -> "HOLDING".equals(i.role())).toList();
        List<String> ahead = new ArrayList<>();
        for (Item i : holdings) {
            Upcoming n = i.earnings() == null ? null : i.earnings().next();
            if (n != null && n.inWindow()) {
                Scenario sc = i.earnings().scenario();
                StatsView st = i.earnings().stats();
                ahead.add(i.symbol() + " " + n.date() + timingText(n.timing()) + "（第 " + n.tradingDaysAhead() + " 个交易日"
                        + (n.estimated() ? "，估计日期" : "") + "）"
                        + (st == null ? "，没有历史样本" : "：历史 " + st.samples() + " 次最大跌 " + st.maxDropPct() + "%")
                        + (sc == null || sc.stressLoss() == null ? "" : "，压力情景 " + money(sc.stressLoss()) + "（净值 " + sc.stressLossPctOfNav() + "%）"));
            }
        }
        List<String> triggered = new ArrayList<>();
        for (Item i : holdings) {
            for (Flag f : i.triggers()) {
                triggered.add(i.symbol() + " " + f.trigger() + "：" + f.detail());
            }
        }
        return List.of(
                new Check("earningsAhead", ahead.isEmpty(), false, ahead.isEmpty()
                        ? "持仓在 " + r.params().windowDays() + " 个交易日内没有财报"
                        : ahead.size() + " 只持仓在 " + r.params().windowDays() + " 个交易日内发财报", ahead.size(), ahead),
                new Check("reviewTriggers", triggered.isEmpty(), false, triggered.isEmpty()
                        ? "持仓没有触发复核条件（按当前持仓与最新 K 线）"
                        : triggered.size() + " 条复核条件触发（只是你设定的条件成立，不是买卖建议；历史验证：成立后并不更差，§29.5）",
                        triggered.size(), triggered));
    }

    // ------------------------------------------------------------------ 计算

    private record Computed(List<TrendReview.Day> days, List<EarningsReaction.Sample> samples, List<EarningsEvent> events,
                            TreeSet<LocalDate> calendar) {
    }

    /** 一次请求共用的交易日历：日 K 的起点到一年多以后（数下次财报在第几个交易日）。 */
    private TreeSet<LocalDate> calendar(LocalDate today) {
        return new TreeSet<>(days.between(Market.US, today.minusYears(HISTORY_YEARS), today.plusDays(400)));
    }

    private Computed compute(InstrumentRow row, LocalDate today, TreeSet<LocalDate> calendar) {
        LocalDate from = today.minusYears(HISTORY_YEARS);
        List<SignalBar> series = structural(row, from, today, calendar);
        List<TrendReview.Day> list = TrendReview.evaluate(series, rules);
        List<EarningsEvent> events = row.type() == SecurityType.STOCK ? earnings.events(row.id()) : List.of();
        LocalDate through = series.getLast().date();
        List<EarningsEvent> past = events.stream().filter(e -> EarningsReaction.reflected(e, through)).toList();
        return new Computed(list, EarningsReaction.samples(series, past), events, calendar);
    }

    private List<SignalBar> structural(InstrumentRow row, LocalDate from, LocalDate to, Set<LocalDate> calendar) {
        List<DailyBar> slice = bars.find(row.instrument(), row.id(), from, to).stream()
                .filter(b -> calendar.contains(b.tradeDate())).toList();
        if (slice.stream().allMatch(DailyBar::blank)) {
            throw new IllegalStateException(row.symbol() + " 没有日 K");
        }
        LocalDate through = slice.getLast().tradeDate();
        StructuralAdjustment.Result adjusted = StructuralAdjustment.apply(slice, rehabs.find(row.instrument(), row.id()), through);
        if (!adjusted.ok()) {
            throw new IllegalStateException(row.symbol() + " 价量口径换算失败：" + adjusted.problem());
        }
        if (adjusted.bars().isEmpty()) {
            throw new IllegalStateException(row.symbol() + " 没有日 K");
        }
        return adjusted.bars();
    }

    private Trend trend(Computed c, PositionSnapshotRow p) {
        List<TrendReview.Day> list = c.days();
        TrendReview.Day d = list.getLast();
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = Math.max(0, list.size() - YEAR_BARS); i < list.size(); i++) {
            hi = Math.max(hi, list.get(i).high());
        }
        boolean ready = d.state() != TrendReview.State.UNAVAILABLE;
        Double cost = p == null || p.averageCost() == null ? null : p.averageCost().doubleValue();
        boolean cross = ready && d.smaShort() < d.smaLong();
        return new Trend(d.date(), round2(d.close()), nan(d.smaShort()), nan(d.smaLong()), nan(d.smaLongPrior()), d.state().name(),
                d.state().label(), gatePass(d), ready ? pct(d.close() / d.smaShort() - 1) : null, ready ? pct(d.close() / d.smaLong() - 1) : null,
                d.belowDays(), cross, cross ? deathCrossSince(list) : null, nan(d.chandelier()),
                d.active().contains(TrendReview.Trigger.CHANDELIER_BREACH), pct(d.close() / hi - 1),
                cost == null || cost <= 0 ? null : pct(d.close() / cost - 1));
    }

    private static boolean gatePass(TrendReview.Day d) {
        return d.state() == TrendReview.State.UP_STRONG || d.state() == TrendReview.State.UP_WEAK;
    }

    /** 当前这段「短均线在长均线之下」从哪天开始。 */
    private static LocalDate deathCrossSince(List<TrendReview.Day> list) {
        LocalDate since = null;
        for (int i = list.size() - 1; i >= 0; i--) {
            TrendReview.Day d = list.get(i);
            if (Double.isNaN(d.smaShort()) || Double.isNaN(d.smaLong()) || d.smaShort() >= d.smaLong()) {
                break;
            }
            since = d.date();
        }
        return since;
    }

    private Upcoming upcoming(Computed c, int window) {
        LocalDate through = c.days().getLast().date();
        EarningsEvent last = null;
        for (EarningsEvent e : c.events()) {
            if (EarningsReaction.reflected(e, through)) {
                last = e;
            } else {
                int ahead = EarningsCalendarHint.tradingDaysBetween(through, e.pubDate(), c.calendar());
                return new Upcoming(e.periodText(), e.pubDate(), e.timing().name(), ahead, false, ahead <= window);
            }
        }
        if (last == null) {
            return null;
        }
        LocalDate est = last.pubDate().plusDays(QUARTER_DAYS);
        int ahead = EarningsCalendarHint.tradingDaysBetween(through, est, c.calendar());
        return new Upcoming(null, est, "UNKNOWN", ahead, true, !est.isBefore(through) && ahead <= window);
    }

    private Scenario scenario(EarningsReaction.Stats stats, BigDecimal mv, BigDecimal nav) {
        if (stats == null) {
            return null;
        }
        double drop = stats.maxDrop();
        double stress = drop * props.stressMultiple();
        Double histLoss = mv == null ? null : round2(-mv.doubleValue() * drop);
        Double stressLoss = mv == null ? null : round2(-mv.doubleValue() * stress);
        Double ofNav = stressLoss == null || nav == null || nav.signum() <= 0 ? null : round2(-stressLoss / nav.doubleValue() * 100);
        Boolean over = props.lossBudgetPct() == null || ofNav == null ? null : ofNav > props.lossBudgetPct().doubleValue();
        return new Scenario(round2(drop * 100), round2(stress * 100), histLoss, stressLoss, ofNav, over);
    }

    private static StatsView statsView(EarningsReaction.Stats s) {
        return s == null ? null : new StatsView(s.samples(), pct(s.meanAbsMove()), pct(s.medianAbsMove()), pct(s.maxDrop()), pct(s.maxRise()),
                s.bigMoves(), s.ups(), s.downs(), s.worstGap() == null ? null : pct(s.worstGap()));
    }

    private static SampleView sampleView(EarningsReaction.Sample s) {
        return new SampleView(s.period(), s.pubDate(), s.timing().name(), s.reactionDate(), s.gap() == null ? null : pct(s.gap()), pct(s.move()));
    }

    // ------------------------------------------------------------------ 格式

    private static double pct(double ratio) {
        return round2(ratio * 100);
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static Double nan(double v) {
        return Double.isNaN(v) ? null : round2(v);
    }

    private static String num(double v) {
        return Double.isNaN(v) ? "—" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String signedPct(double ratio) {
        return String.format(Locale.ROOT, "%+.2f%%", ratio * 100);
    }

    private static String money(Double v) {
        return v == null ? "—" : String.format(Locale.ROOT, "%,.0f", v);
    }

    private static String timingText(String timing) {
        return switch (timing) {
            case "PRE_MARKET" -> " 盘前";
            case "DURING_MARKET" -> " 盘中";
            case "AFTER_MARKET" -> " 盘后";
            default -> "（时段未知）";
        };
    }
}
