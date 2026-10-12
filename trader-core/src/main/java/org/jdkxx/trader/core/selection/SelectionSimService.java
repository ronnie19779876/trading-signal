package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.core.marketdata.audit.BarAuditService;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.selection.SelectionModel;
import org.jdkxx.trader.domain.selection.sim.SimLedger;
import org.jdkxx.trader.domain.selection.sim.SimPath;
import org.jdkxx.trader.domain.selection.sim.SimRules;
import org.jdkxx.trader.domain.selection.sim.SimSetup;
import org.jdkxx.trader.domain.signal.SentinelEvaluation;
import org.jdkxx.trader.domain.signal.SignalBar;
import org.jdkxx.trader.domain.signal.SignalInputs;
import org.jdkxx.trader.domain.signal.SignalTrades;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.jdkxx.trader.storage.marketdata.TradingDayRepository;
import org.jdkxx.trader.storage.selection.SelectionScoreRepository;
import org.jdkxx.trader.storage.selection.SelectionSimRepository;
import org.jdkxx.trader.storage.selection.SimTradeRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 量化选股·模拟交易（ARCHITECTURE §26）：验证选股准不准用的纸面账户，<b>不下单、不连网关</b>，只用库里的日 K 记账。
 *
 * <ul>
 *   <li>加入：只有最新评分日合成分前 {@code topN} 名（已评级）能加；止损、压力区按评分日定下、之后不变；
 *       点击之后的第一个开盘价入场，同一只同时只能有一笔，最多 {@code slots} 笔（含待成交）；</li>
 *   <li>每天（量化选股每日作业之后）：未完结的逐笔从加入时的价位<b>整段重算</b>到截止日（{@link SimPath}），
 *       待成交的轮到入场日就按等权定股数；止损、吊灯止损自动平仓，止盈只提示；再写逐日权益；</li>
 *   <li>手动平仓：点击之后的第一个开盘价成交；还没入场的直接撤销；</li>
 *   <li>每周：周报快照落库，带 SPY 与范围等权的同期对照、逐笔与影子变体的配对。</li>
 * </ul>
 * 加入、平仓与重算共用一把锁：重算读出一笔、改完写回的过程中不能有平仓请求插进来被覆盖掉。
 */
public class SelectionSimService {

    private static final Logger log = LoggerFactory.getLogger(SelectionSimService.class);
    /** 常规时段开盘（美东）：之前点的当天开盘成交，之后点的下一个交易日开盘 */
    static final LocalTime REGULAR_OPEN = LocalTime.of(9, 30);
    /** 周报里的固定说明（不是统计结论） */
    static final List<String> CAVEATS = List.of(
            "不计佣金与滑点；价格只按结构性事件调整、不含普通分红，SPY 与范围等权对照同口径",
            "止损按收盘判定、按收盘成交，跳空跌穿照实记；入场与手动平仓按点击后的第一个开盘价",
            "同一周买入的标的高度相关，样本数 n 远小于笔数所暗示的独立样本数；几周的结果与噪声分不开",
            "只做多：上涨市里绝对盈利首先来自大盘，判断选股看对 SPY 的超额，不看绝对盈亏",
            "本周平仓的影子变体（不手动平、只按止损走）的结果截至生成时（generatedThrough）；影子还没平仓的不参与配对");

    private final SimRules rules = SimRules.V1;
    private final SelectionSimRepository sim;
    private final SelectionScoreRepository scores;
    private final SelectionTargets targets;
    private final InstrumentRepository instruments;
    private final DailyBarRepository bars;
    private final RehabFactorRepository rehabs;
    private final TradingDayRepository days;
    private final Supplier<LocalDate> settledCutoff;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final Clock clock;
    private final ZoneId zone;
    private final ReentrantLock lock = new ReentrantLock();

    public SelectionSimService(SelectionSimRepository sim, SelectionScoreRepository scores, SelectionTargets targets,
                               InstrumentRepository instruments, DailyBarRepository bars, RehabFactorRepository rehabs,
                               TradingDayRepository days, Supplier<LocalDate> settledCutoff,
                               com.fasterxml.jackson.databind.ObjectMapper json, Clock clock, ZoneId zone) {
        this.sim = sim;
        this.scores = scores;
        this.targets = targets;
        this.instruments = instruments;
        this.bars = bars;
        this.rehabs = rehabs;
        this.days = days;
        this.settledCutoff = settledCutoff;
        this.json = json;
        this.clock = clock;
        this.zone = zone;
    }

    public SimRules rules() {
        return rules;
    }

    // ------------------------------------------------------------------ 加入与平仓

    /** 最新评分日的前 N 名及能否加入。 */
    public record Candidate(int rank, long instrumentId, String symbol, String rating, BigDecimal composite, String state, Long tradeId) {
    }

    public record Candidates(LocalDate scoreDate, int active, int slots, List<Candidate> top) {
    }

    public Candidates candidates() {
        LocalDate d = scores.latestDate(SelectionModel.VERSION).orElse(null);
        int active = sim.activeCount();
        if (d == null) {
            return new Candidates(null, active, rules.slots(), List.of());
        }
        Map<Long, SimTradeRow> activeBy = new HashMap<>();
        sim.list(null).stream().filter(SimTradeRow::active).forEach(t -> activeBy.put(t.instrumentId(), t));
        List<Candidate> out = new ArrayList<>();
        List<SelectionScoreRepository.ScoreView> top = topOf(d);
        for (int i = 0; i < top.size(); i++) {
            SelectionScoreRepository.ScoreView s = top.get(i);
            SimTradeRow t = activeBy.get(s.instrumentId());
            String state = t != null ? "ACTIVE" : active >= rules.slots() ? "FULL" : "AVAILABLE";
            out.add(new Candidate(i + 1, s.instrumentId(), s.symbol(), s.rating(), s.composite(), state, t == null ? null : t.id()));
        }
        return new Candidates(d, active, rules.slots(), out);
    }

    /** 加入前的预览：定下的价位、最早入场日、按评分日收盘估的股数（真实股数在入场那天按当时的现金定）。 */
    public record Preview(String symbol, int rank, LocalDate scoreDate, String rating, BigDecimal composite, SimSetup setup,
                          LocalDate plannedEntryDate, double estimatedNotional, int estimatedShares, double cashNow) {
    }

    public Preview preview(String symbol) {
        Eligible e = eligible(symbol);
        LocalDate planned = nextOpenDate(Instant.now(clock));
        Cash c = cashNow();
        double notional = Math.min((c.initial() + c.realized()) / rules.slots(), c.cash());
        return new Preview(e.row().symbol(), e.rank(), e.scoreDate(), e.score().rating(), e.score().composite(), e.setup(), planned,
                round2(notional), SimLedger.shares(c.initial() + c.realized(), rules.slots(), c.cash(), e.setup().close()), round2(c.cash()));
    }

    public SimTradeRow add(String symbol) {
        return locked(() -> {
            Eligible e = eligible(symbol);
            Instant now = Instant.now(clock);
            LocalDate planned = nextOpenDate(now);
            SimSetup s = e.setup();
            SimTradeRow row = SimTradeRow.pending(rules.version(), e.row().id(), SelectionModel.VERSION, e.scoreDate(), e.rank(),
                    e.score().rating(), e.score().composite(), now, d6(s.close()), d6(s.atr()), d6(s.formulaStop()), d6(s.stop()),
                    s.leg().name(), s.resistance() == null ? null : d6(s.resistance()), planned);
            long id = sim.insert(row);
            sim.event(id, "ADDED", e.row().symbol() + " 评分日 " + e.scoreDate() + " 第 " + e.rank() + " 名（" + e.score().rating()
                    + "，合成分 " + e.score().composite() + "）；止损 " + fmt(s.stop()) + "（" + legText(s) + "），压力区底 "
                    + (s.resistance() == null ? "无" : fmt(s.resistance())) + "；最早入场日 " + planned + " 开盘");
            return sim.find(id).orElseThrow();
        });
    }

    /** 还没入场的撤销；持有中的记下平仓请求，点击之后的第一个开盘价成交（止盈提示过的记 TAKE_PROFIT，否则 MANUAL）。 */
    public SimTradeRow requestClose(long id, String note) {
        return locked(() -> {
            SimTradeRow t = sim.find(id).orElseThrow(() -> new NoSuchElementException("没有这笔模拟交易：#" + id));
            if (!t.active()) {
                throw new IllegalStateException("#" + id + " 已经是 " + t.status() + "，不能再平仓");
            }
            if (t.closeRequestedAt() != null) {
                throw new IllegalStateException("#" + id + " 已在 " + t.closeRequestedAt() + " 请求过平仓，计划 " + t.plannedExitDate() + " 开盘成交");
            }
            Instant now = Instant.now(clock);
            LocalDate exitDate = nextOpenDate(now);
            String clean = note == null || note.isBlank() ? null : note.trim();
            // 还没到入场那个开盘：直接撤销。已经过了入场开盘（只是作业还没把它记成持有）：照常记平仓请求
            if ("PENDING_ENTRY".equals(t.status()) && !exitDate.isAfter(t.plannedEntryDate())) {
                SimTradeRow c = with(t, "CANCELLED", "手动撤销（尚未入场）" + (clean == null ? "" : "：" + clean));
                sim.update(c);
                sim.event(id, "CANCELLED", t.symbol() + " 入场前手动撤销" + (clean == null ? "" : "：" + clean));
                return sim.find(id).orElseThrow();
            }
            String kind = t.targetHitDate() != null ? "TAKE_PROFIT" : "MANUAL";
            SimTradeRow r = new SimTradeRow(t.id(), t.rulesVersion(), t.instrumentId(), t.symbol(), t.modelVersion(), t.scoreDate(),
                    t.scoreRank(), t.rating(), t.composite(), t.requestedAt(), t.setupClose(), t.setupAtr(), t.formulaStop(), t.stop(),
                    t.stopLeg(), t.resistance(), t.plannedEntryDate(), t.status(), t.entryDate(), t.entryPrice(), t.shares(), t.plusOneR(),
                    t.target(), t.targetSource(), t.touchedPlusOneR(), t.targetHitDate(), t.activeStop(), t.chandelierActive(),
                    now, exitDate, kind, clean, t.exitDate(), t.exitPrice(), t.exitReason(), t.pnl(), t.returnPct(), t.rMultiple(),
                    t.mfeR(), t.maeR(), t.barsHeld(), t.lastClose(), t.lastCloseDate(), t.unrealizedPnl(), t.spyReturn(), t.excessReturn(),
                    t.shadowStatus(), t.shadowExitDate(), t.shadowExitPrice(), t.shadowExitReason(), t.shadowPnl(), t.shadowReturn(),
                    t.shadowLastClose(), t.cancelReason(), t.updatedThrough(), t.updatedAt());
            sim.update(r);
            sim.event(id, "CLOSE_REQUESTED", t.symbol() + ("TAKE_PROFIT".equals(kind) ? " 止盈" : " 手动") + "平仓请求，计划 " + exitDate
                    + " 开盘成交" + (clean == null ? "" : "：" + clean));
            return sim.find(id).orElseThrow();
        });
    }

    private record Eligible(InstrumentRow row, int rank, LocalDate scoreDate, SelectionScoreRepository.ScoreView score, SimSetup setup) {
    }

    private Eligible eligible(String symbol) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
        LocalDate d = scores.latestDate(SelectionModel.VERSION).orElseThrow(() -> new IllegalStateException("还没有任何评分结果"));
        List<SelectionScoreRepository.ScoreView> top = topOf(d);
        int rank = -1;
        for (int i = 0; i < top.size(); i++) {
            if (top.get(i).symbol().equalsIgnoreCase(sym)) {
                rank = i + 1;
            }
        }
        if (rank < 0) {
            throw new IllegalArgumentException(sym + " 不在最新评分日 " + d + " 合成分前 " + rules.topN() + " 名里");
        }
        SelectionScoreRepository.ScoreView score = top.get(rank - 1);
        InstrumentRow row = instruments.findById(score.instrumentId()).orElseThrow();
        sim.activeFor(row.id()).ifPresent(t -> {
            throw new IllegalStateException(sym + " 已在模拟池里（#" + t.id() + "，" + t.status() + "）");
        });
        int active = sim.activeCount();
        if (active >= rules.slots()) {
            throw new IllegalStateException("模拟池已满：进行中 " + active + " 笔（上限 " + rules.slots() + "）");
        }
        return new Eligible(row, rank, d, score, setup(row, d));
    }

    /** 评分日的止损与压力区：沿用入场信号第四门与出场预案（同一套数据层检查与结构口径）。 */
    SimSetup setup(InstrumentRow row, LocalDate scoreDate) {
        LocalDate from = scoreDate.minusDays(rules.sentinel().windowCalendarDays());
        List<DailyBar> raw = bars.find(row.instrument(), row.id(), from, scoreDate);
        List<RehabFactor> factors = rehabs.find(row.instrument(), row.id());
        Set<LocalDate> calendar = new HashSet<>(days.between(Market.US, from, scoreDate));
        SentinelEvaluation e = SignalInputs.judge(raw, factors, calendar, scoreDate, rules.sentinel());
        return SimSetup.from(e, rules);
    }

    private List<SelectionScoreRepository.ScoreView> topOf(LocalDate d) {
        return scores.day(SelectionModel.VERSION, d, null, null).stream().filter(s -> s.rating() != null && s.exclusion() == null)
                .limit(rules.topN()).toList();
    }

    /**
     * 点击之后第一个常规时段开盘的日期：交易日 09:30（美东）前点的是当天，否则下一个交易日。
     * 日历没覆盖到未来时跳过周末给工作日（节假日认不出来，入场时取不早于它的第一根 K 线，结果仍对）。
     */
    LocalDate nextOpenDate(Instant at) {
        ZonedDateTime now = at.atZone(zone);
        LocalDate today = now.toLocalDate();
        LocalDate candidate = now.toLocalTime().isBefore(REGULAR_OPEN) ? today : today.plusDays(1);
        List<LocalDate> ahead = days.between(Market.US, candidate, candidate.plusDays(14));
        if (!ahead.isEmpty()) {
            return ahead.getFirst();
        }
        while (candidate.getDayOfWeek().getValue() >= 6) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    // ------------------------------------------------------------------ 每日重算

    /** 有进行中的笔数却还没算到 through：每日作业的补偿与审计用。 */
    public boolean stale(LocalDate through) {
        return sim.unfinished().stream().anyMatch(t -> t.updatedThrough() == null || t.updatedThrough().isBefore(through));
    }

    /**
     * 把未完结的逐笔算到 {@code through}，再补齐逐日权益。<b>只往前走</b>：已算到更晚日期的跳过（补跑旧日期不回退状态）。
     * 先重算持有中的（它们的平仓会释放现金），再按入场日、请求时间先后给待成交的定股数。
     */
    public String update(LocalDate through, JobContext ctx) {
        return locked(() -> doUpdate(through, ctx));
    }

    private String doUpdate(LocalDate through, JobContext ctx) {
        double initial = sim.account().initialCapital().doubleValue();
        List<SimTradeRow> todo = sim.unfinished().stream()
                .filter(t -> t.updatedThrough() == null || t.updatedThrough().isBefore(through))
                .filter(t -> !t.scoreDate().isAfter(through))
                .toList();
        Map<Long, SimLedger.Fill> fills = new LinkedHashMap<>();
        sim.filled().forEach(t -> fills.put(t.id(), fill(t)));
        Spy spy = spy(through);
        Map<Long, NavigableMap<LocalDate, Double>> closes = new HashMap<>();
        int filled = 0;
        int closed = 0;
        int cancelled = 0;
        int failed = 0;
        List<SimTradeRow> ordered = new ArrayList<>(todo.stream().filter(t -> !"PENDING_ENTRY".equals(t.status())).toList());
        ordered.addAll(todo.stream().filter(t -> "PENDING_ENTRY".equals(t.status())).toList());
        for (SimTradeRow t : ordered) {
            ctx.progress("模拟交易重算 " + t.symbol());
            try {
                List<SignalBar> series = series(t, through);
                SimPath.Path actual = SimPath.walk(series, t.scoreDate(), t.plannedEntryDate(), t.stop().doubleValue(),
                        t.resistance() == null ? null : t.resistance().doubleValue(), t.plannedExitDate(), rules);
                SimPath.Path shadow = t.plannedExitDate() == null ? actual
                        : SimPath.walk(series, t.scoreDate(), t.plannedEntryDate(), t.stop().doubleValue(),
                        t.resistance() == null ? null : t.resistance().doubleValue(), null, rules);
                Integer shares = t.shares();
                if ("PENDING_ENTRY".equals(t.status())) {
                    if (actual.state() == SimPath.State.PENDING) {
                        sim.update(through(t, through));
                        continue;
                    }
                    if (actual.state() == SimPath.State.CANCELLED) {
                        sim.update(through(with(t, "CANCELLED", actual.cancelReason()), through));
                        sim.event(t.id(), "CANCELLED", t.symbol() + " " + actual.entryDate() + " " + actual.cancelReason());
                        cancelled++;
                        continue;
                    }
                    LocalDate e = actual.entryDate();
                    double capital = initial + SimLedger.realizedBeforeOpen(fills.values(), e);
                    double cash = SimLedger.cashAtOpen(fills.values(), e, t.requestedAt(), initial);
                    shares = SimLedger.shares(capital, rules.slots(), cash, actual.entry());
                    if (shares <= 0) {
                        String why = e + " 开盘可用现金 " + fmt2(cash) + " 不够买 1 股（" + fmt(actual.entry()) + "）";
                        sim.update(through(with(t, "CANCELLED", why), through));
                        sim.event(t.id(), "CANCELLED", t.symbol() + " " + why);
                        cancelled++;
                        continue;
                    }
                    sim.event(t.id(), "FILLED", t.symbol() + " " + e + " 开盘 " + fmt(actual.entry()) + " 买入 " + shares + " 股（名义 "
                            + fmt2(shares * actual.entry()) + "；等权额度 " + fmt2(capital / rules.slots()) + "、可用现金 " + fmt2(cash) + "）");
                    filled++;
                }
                SimTradeRow next = build(t, actual, shadow, shares, spy, through);
                if (next.targetHitDate() != null && t.targetHitDate() == null) {
                    sim.event(t.id(), "TARGET_HIT", t.symbol() + " " + next.targetHitDate() + " 盘中触及止盈目标 " + fmt(next.target().doubleValue())
                            + "（" + ("RESISTANCE".equals(next.targetSource()) ? "压力区底" : "+" + rules.targetFallbackR() + "R") + "），可手动平仓");
                }
                if ("CLOSED".equals(next.status()) && !"CLOSED".equals(t.status())) {
                    sim.event(t.id(), "CLOSED", t.symbol() + " " + next.exitDate() + " " + reasonText(next.exitReason()) + " " + fmt(next.exitPrice().doubleValue())
                            + "，盈亏 " + next.pnl() + "（" + pct(next.returnPct()) + "，" + next.rMultiple().setScale(2, RoundingMode.HALF_UP) + "R）");
                    closed++;
                }
                sim.update(next);
                fills.put(t.id(), fill(next));
                closes.put(t.id(), actual.closes());
            } catch (RuntimeException ex) {
                failed++;
                log.warn("模拟交易 #{} {} 重算失败：{}", t.id(), t.symbol(), ex.toString());
                ctx.partial("#" + t.id() + " " + t.symbol() + " 重算失败：" + ex.getMessage());
            }
        }
        int equityDays = writeEquity(through, fills, closes, initial);
        return "模拟交易算到 " + through + "：重算 " + todo.size() + " 笔，新入场 " + filled + "、平仓 " + closed + "、取消 " + cancelled
                + (failed > 0 ? "、失败 " + failed : "") + "；逐日权益写 " + equityDays + " 天";
    }

    /** 评分日口径的日 K：取数起点固定为评分日往前 600 自然日（Wilder ATR 与起点有关），按截止日结构换算后折回评分日价格尺度。 */
    private List<SignalBar> series(SimTradeRow t, LocalDate through) {
        InstrumentRow row = instruments.findById(t.instrumentId()).orElseThrow();
        LocalDate from = t.scoreDate().minusDays(rules.sentinel().windowCalendarDays());
        List<DailyBar> raw = bars.find(row.instrument(), row.id(), from, through);
        List<RehabFactor> factors = rehabs.find(row.instrument(), row.id());
        Set<LocalDate> calendar = new HashSet<>(days.between(Market.US, from, through));
        return SignalTrades.scaledTo(raw, factors, calendar, through, t.scoreDate());
    }

    private SimTradeRow build(SimTradeRow t, SimPath.Path a, SimPath.Path s, int shares, Spy spy, LocalDate through) {
        double entry = a.entry();
        double stop = t.stop().doubleValue();
        double risk = entry - stop;
        boolean closed = a.state() == SimPath.State.CLOSED;
        String reason = !closed ? null : a.reason() == SimPath.ExitReason.REQUESTED ? t.closeKind() : a.reason().name();
        Double pnl = closed ? shares * (a.exit() - entry) : null;
        Double ret = closed ? a.exit() / entry - 1 : null;
        Double unreal = closed ? null : shares * (a.lastClose() - entry);
        Double spyRet = closed
                ? spy.ret(a.entryDate(), a.exitDate(), a.reason() == SimPath.ExitReason.REQUESTED)
                : spy.ret(a.entryDate(), a.lastCloseDate(), false);
        double heldRet = closed ? ret : a.lastClose() / entry - 1;
        boolean shadowClosed = s.state() == SimPath.State.CLOSED;
        return new SimTradeRow(t.id(), t.rulesVersion(), t.instrumentId(), t.symbol(), t.modelVersion(), t.scoreDate(), t.scoreRank(),
                t.rating(), t.composite(), t.requestedAt(), t.setupClose(), t.setupAtr(), t.formulaStop(), t.stop(), t.stopLeg(),
                t.resistance(), t.plannedEntryDate(),
                closed ? "CLOSED" : "OPEN", a.entryDate(), d6(entry), shares, d6(a.plusOneR()), d6(a.target()), a.targetSource().name(),
                a.touchedPlusOneR(), a.targetHitDate(), d6(a.activeStop()), a.chandelierActive(),
                t.closeRequestedAt(), t.plannedExitDate(), t.closeKind(), t.closeNote(),
                closed ? a.exitDate() : null, closed ? d6(a.exit()) : null, reason, pnl == null ? null : d2(pnl), ret == null ? null : d6(ret),
                closed ? d6((a.exit() - entry) / risk) : null, d6((a.highest() - entry) / risk), d6((a.lowest() - entry) / risk), a.barsHeld(),
                d6(a.lastClose()), a.lastCloseDate(), unreal == null ? null : d2(unreal),
                spyRet == null ? null : d6(spyRet), spyRet == null ? null : d6(heldRet - spyRet),
                shadowClosed ? "CLOSED" : "OPEN", shadowClosed ? s.exitDate() : null, shadowClosed ? d6(s.exit()) : null,
                shadowClosed ? s.reason().name() : null, shadowClosed ? d2(shares * (s.exit() - entry)) : null,
                shadowClosed ? d6(s.exit() / entry - 1) : null, d6(s.lastClose()),
                null, through, t.updatedAt());
    }

    /** 补齐逐日权益：从已写的最后一天之后（没有就从第一笔入场日）到 through。 */
    private int writeEquity(LocalDate through, Map<Long, SimLedger.Fill> fills, Map<Long, NavigableMap<LocalDate, Double>> closes,
                            double initial) {
        if (fills.isEmpty()) {
            return 0;
        }
        LocalDate first = fills.values().stream().map(SimLedger.Fill::entryDate).min(LocalDate::compareTo).orElseThrow();
        List<SelectionSimRepository.EquityRow> existing = sim.equity(first, through);
        // 今天重算过的笔数，持有期内的权益都可能变：从它们最早的入场日起重写；否则只补没写过的日子
        LocalDate from = closes.keySet().stream().map(fills::get).map(SimLedger.Fill::entryDate).min(LocalDate::compareTo)
                .orElse(existing.isEmpty() ? first : existing.getLast().tradeDate().plusDays(1));
        if (from.isBefore(first)) {
            from = first;
        }
        List<LocalDate> span = days.between(Market.US, from, through);
        if (span.isEmpty()) {
            return 0;
        }
        // 这次没重算的已平仓笔（持有期与区间重叠）也要逐日收盘
        for (SimLedger.Fill f : fills.values()) {
            boolean overlaps = !f.entryDate().isAfter(through) && (f.exitDate() == null || !f.exitDate().isBefore(from));
            if (overlaps && !closes.containsKey(f.id())) {
                sim.find(f.id()).ifPresent(t -> closes.put(f.id(), closesOf(t, through)));
            }
        }
        List<SelectionSimRepository.EquityRow> rows = new ArrayList<>();
        for (LocalDate d : span) {
            SimLedger.Equity e = SimLedger.equityAtClose(fills.values(), d, initial, closes);
            rows.add(new SelectionSimRepository.EquityRow(d, d2(e.cash()), d2(e.positions()), d2(e.total()), e.open()));
        }
        sim.upsertEquity(rows);
        return rows.size();
    }

    private NavigableMap<LocalDate, Double> closesOf(SimTradeRow t, LocalDate through) {
        LocalDate end = t.exitDate() != null && t.exitDate().isBefore(through) ? t.exitDate() : through;
        return SimPath.walk(series(t, end), t.scoreDate(), t.plannedEntryDate(), t.stop().doubleValue(),
                t.resistance() == null ? null : t.resistance().doubleValue(), t.plannedExitDate(), rules).closes();
    }

    private static SimLedger.Fill fill(SimTradeRow t) {
        return new SimLedger.Fill(t.id(), t.requestedAt(), t.entryDate(), t.entryPrice().doubleValue(), t.shares(), t.exitDate(),
                t.exitPrice() == null ? null : t.exitPrice().doubleValue(),
                "TAKE_PROFIT".equals(t.exitReason()) || "MANUAL".equals(t.exitReason()));
    }

    // ------------------------------------------------------------------ SPY 对照

    /** SPY 日 K（结构口径，截止日基准）：同一持有期的收益只看比值，与尺度无关。 */
    record Spy(NavigableMap<LocalDate, SignalBar> bars) {

        /** 入场日开盘 → 平仓价同一时点（开盘或收盘）；缺 K 线按之前最近一根；没有数据为 null。 */
        Double ret(LocalDate entryDate, LocalDate exitDate, boolean exitAtOpen) {
            Map.Entry<LocalDate, SignalBar> in = bars.floorEntry(entryDate);
            Map.Entry<LocalDate, SignalBar> out = bars.floorEntry(exitDate);
            if (in == null || out == null || !in.getKey().equals(entryDate)) {
                return null;
            }
            double exit = exitAtOpen && out.getKey().equals(exitDate) ? out.getValue().open() : out.getValue().close();
            return exit / in.getValue().open() - 1;
        }

        Double closeToClose(LocalDate from, LocalDate to) {
            Map.Entry<LocalDate, SignalBar> a = bars.floorEntry(from);
            Map.Entry<LocalDate, SignalBar> b = bars.floorEntry(to);
            return a == null || b == null ? null : b.getValue().close() / a.getValue().close() - 1;
        }
    }

    private Spy spy(LocalDate through) {
        Optional<InstrumentRow> row = instruments.find(Instrument.us("SPY"));
        if (row.isEmpty()) {
            return new Spy(new TreeMap<>());
        }
        LocalDate from = sim.list(null).stream().map(SimTradeRow::scoreDate).min(LocalDate::compareTo).orElse(through).minusDays(10);
        List<DailyBar> raw = bars.find(row.get().instrument(), row.get().id(), from, through);
        Set<LocalDate> calendar = new HashSet<>(days.between(Market.US, from, through));
        NavigableMap<LocalDate, SignalBar> m = new TreeMap<>();
        if (!raw.isEmpty()) {
            SignalTrades.scaledTo(raw, rehabs.find(row.get().instrument(), row.get().id()), calendar, through, through)
                    .forEach(b -> m.put(b.date(), b));
        }
        return new Spy(m);
    }

    // ------------------------------------------------------------------ 查询

    private record Cash(double initial, double cash, double realized, double cost) {
    }

    private Cash cashNow() {
        double initial = sim.account().initialCapital().doubleValue();
        double cash = initial;
        double realized = 0;
        double cost = 0;
        for (SimTradeRow t : sim.filled()) {
            double c = t.shares() * t.entryPrice().doubleValue();
            cash -= c;
            if (t.exitPrice() != null) {
                cash += t.shares() * t.exitPrice().doubleValue();
                realized += t.pnl() == null ? 0 : t.pnl().doubleValue();
            } else {
                cost += c;
            }
        }
        return new Cash(initial, cash, realized, cost);
    }

    /** 账户概况：现金、持仓（按各笔最近收盘）、已实现与浮动盈亏、自第一笔入场以来的收益与 SPY 同期。 */
    public record Overview(Map<String, Object> rules, SelectionSimRepository.Account account, LocalDate startDate, double cash,
                           double positionsValue, double equity, double realized, double unrealized, Double totalReturn,
                           Double spySinceStart, LocalDate markedThrough, int open, int pending, int closed, int cancelled,
                           int targetAlerts, Candidates candidates) {
    }

    public Overview overview() {
        Cash c = cashNow();
        List<SimTradeRow> all = sim.list(null);
        double positions = 0;
        double unrealized = 0;
        LocalDate marked = null;
        for (SimTradeRow t : all) {
            if ("OPEN".equals(t.status()) && t.lastClose() != null) {
                positions += t.shares() * t.lastClose().doubleValue();
                unrealized += t.unrealizedPnl() == null ? 0 : t.unrealizedPnl().doubleValue();
                marked = marked == null || t.lastCloseDate().isBefore(marked) ? t.lastCloseDate() : marked;
            }
        }
        LocalDate start = all.stream().map(SimTradeRow::entryDate).filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        double equity = c.cash() + positions;
        Double spy = null;
        if (start != null) {
            LocalDate end = settledCutoff.get();
            Spy s = spy(end);
            Map.Entry<LocalDate, SignalBar> in = s.bars().floorEntry(start);
            Map.Entry<LocalDate, SignalBar> out = s.bars().floorEntry(end);
            spy = in == null || out == null || !in.getKey().equals(start) ? null : out.getValue().close() / in.getValue().open() - 1;
        }
        return new Overview(rules.toMap(), sim.account(), start, round2(c.cash()), round2(positions), round2(equity), round2(c.realized()),
                round2(unrealized), start == null ? null : equity / c.initial() - 1, spy, marked,
                count(all, "OPEN"), count(all, "PENDING_ENTRY"), count(all, "CLOSED"), count(all, "CANCELLED"),
                (int) all.stream().filter(t -> "OPEN".equals(t.status()) && t.targetHitDate() != null && t.closeRequestedAt() == null).count(),
                candidates());
    }

    public List<SimTradeRow> trades(String status) {
        return sim.list(status == null ? null : status.trim().toUpperCase(java.util.Locale.ROOT));
    }

    public record TradeDetail(SimTradeRow trade, List<SelectionSimRepository.Event> events) {
    }

    public TradeDetail trade(long id) {
        return new TradeDetail(sim.find(id).orElseThrow(() -> new NoSuchElementException("没有这笔模拟交易：#" + id)), sim.events(id, 200));
    }

    public List<SelectionSimRepository.Event> events(int limit) {
        return sim.events(null, Math.clamp(limit, 1, 1000));
    }

    public List<SelectionSimRepository.EquityRow> equity(LocalDate from, LocalDate to) {
        return sim.equity(from, to);
    }

    // ------------------------------------------------------------------ 周报

    /** 某周的报表正文（落库为 jsonb，生成后不变）。比例都是小数（0.012 = 1.2%）。 */
    public record WeeklyReport(String rulesVersion, LocalDate weekStart, LocalDate weekEnd, LocalDate accountStart,
                               double initialCapital, double equityStart, double equityEnd, double cashEnd, double weekPnl,
                               Double weekReturn, Double cumulativeReturn, double realizedWeek, double unrealizedEnd, int openAtEnd,
                               Double spyWeekReturn, Double spySinceStart, Double universeWeekReturn, int universeCount,
                               Double maxDrawdown, List<WeeklyTrade> closedThisWeek, Totals allClosed, List<WeeklyOpen> openPositions,
                               LocalDate generatedThrough, List<String> caveats) {
    }

    /**
     * 周末仍持有的一笔，<b>按周末那天的收盘</b>估值（不是生成时的最新状态：补生成旧周报时不能把之后的平仓、价格带进来）。
     *
     * @param targetHitDate 周末之前（含）触及止盈目标的日期，没有为 null
     */
    public record WeeklyOpen(long id, String symbol, LocalDate entryDate, double entry, int shares, LocalDate markDate, Double mark,
                             Double unrealizedPnl, Double returnPct, Double spyReturn, Double excessReturn, LocalDate targetHitDate) {
    }

    /** 逐笔：实际与影子变体（只按止损走、不手动平）并列，配对看手动止盈 / 平仓的得失。 */
    public record WeeklyTrade(long id, String symbol, LocalDate entryDate, double entry, int shares, LocalDate exitDate, Double exit,
                              String reason, Double pnl, Double returnPct, Double rMultiple, Double spyReturn, Double excessReturn,
                              String shadowStatus, Double shadowPnl, Double shadowReturn) {
    }

    /** 自开始以来全部已平仓：笔数、胜负、平均收益与平均超额（n 照实给，不做显著性判断）。 */
    public record Totals(int n, int wins, int losses, double pnl, Double meanReturn, Double meanExcess, int withExcess,
                         int manualClosed, Double manualMinusShadow, int manualPaired) {
    }

    public List<SelectionSimRepository.WeeklyRow> weeklies(int limit) {
        return sim.weeklies(Math.clamp(limit, 1, 520));
    }

    public SelectionSimRepository.WeeklyRow weeklyRow(LocalDate weekEnd) {
        return sim.weekly(weekEnd).orElseThrow(() -> new NoSuchElementException(weekEnd + " 这一周还没有周报"));
    }

    /** 生成某周的周报（weekEnd 为空 = 最近一个已收盘落定的交易日所在周）。已有的不覆盖，除非 force（留痕）。 */
    public String weekly(LocalDate weekEnd, boolean force, JobContext ctx) {
        return locked(() -> {
            LocalDate cutoff = settledCutoff.get();
            LocalDate anchor = weekEnd == null ? cutoff : weekEnd;
            LocalDate monday = anchor.minusDays(anchor.getDayOfWeek().getValue() - 1);
            List<LocalDate> week = days.between(Market.US, monday, monday.plusDays(4));
            if (week.isEmpty()) {
                return monday + " 这一周没有交易日，不出周报";
            }
            LocalDate end = week.getLast();
            if (end.isAfter(cutoff)) {
                throw new IllegalArgumentException(monday + " 这一周还没结束（最后一个交易日 " + end + "，当前收盘落定到 " + cutoff + "）");
            }
            if (sim.weekly(end).isPresent() && !force) {
                return end + " 的周报已存在，未覆盖（要重新生成用 force=true）";
            }
            doUpdate(cutoff, ctx);
            WeeklyReport r = buildWeekly(week.getFirst(), end);
            if (r == null) {
                return end + " 之前还没有任何入场，不出周报";
            }
            try {
                sim.saveWeekly(new SelectionSimRepository.WeeklyRow(end, week.getFirst(), rules.version(), d2(r.equityEnd()),
                        r.weekReturn() == null ? null : d6(r.weekReturn()), r.spyWeekReturn() == null ? null : d6(r.spyWeekReturn()),
                        r.closedThisWeek().size(), json.writeValueAsString(r), null));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            sim.event(null, force ? "WEEKLY_REGENERATED" : "WEEKLY_REPORT", end + " 周报：权益 " + fmt2(r.equityEnd()) + "，本周 "
                    + pct(r.weekReturn()) + "（SPY " + pct(r.spyWeekReturn()) + "），本周平仓 " + r.closedThisWeek().size() + " 笔");
            return end + " 周报已生成：本周 " + pct(r.weekReturn()) + "，SPY " + pct(r.spyWeekReturn()) + "，本周平仓 " + r.closedThisWeek().size() + " 笔";
        });
    }

    WeeklyReport buildWeekly(LocalDate start, LocalDate end) {
        double initial = sim.account().initialCapital().doubleValue();
        List<SimTradeRow> filled = sim.filled();
        LocalDate accountStart = filled.stream().map(SimTradeRow::entryDate).min(LocalDate::compareTo).orElse(null);
        if (accountStart == null || accountStart.isAfter(end)) {
            return null;
        }
        List<SelectionSimRepository.EquityRow> curve = sim.equity(accountStart, end);
        if (curve.isEmpty() || !curve.getLast().tradeDate().equals(end)) {
            throw new IllegalStateException(end + " 的逐日权益还没有（先跑重算）");
        }
        SelectionSimRepository.EquityRow last = curve.getLast();
        double equityStart = curve.stream().filter(e -> e.tradeDate().isBefore(start)).reduce((a, b) -> b)
                .map(e -> e.equity().doubleValue()).orElse(initial);
        double equityEnd = last.equity().doubleValue();

        List<WeeklyTrade> closedWeek = new ArrayList<>();
        List<SimTradeRow> openRows = new ArrayList<>();
        List<SimTradeRow> closedAll = new ArrayList<>();
        double realizedWeek = 0;
        double costOpen = 0;
        for (SimTradeRow t : filled) {
            if (t.exitDate() != null && !t.exitDate().isAfter(end)) {
                closedAll.add(t);
                if (!t.exitDate().isBefore(start)) {
                    closedWeek.add(weeklyTrade(t));
                    realizedWeek += t.pnl().doubleValue();
                }
            } else if (!t.entryDate().isAfter(end)) {
                openRows.add(t);
                costOpen += t.shares() * t.entryPrice().doubleValue();
            }
        }
        Spy spy = spy(end);
        List<WeeklyOpen> open = new ArrayList<>();
        for (SimTradeRow t : openRows) {
            Map.Entry<LocalDate, Double> m = closesOf(t, end).floorEntry(end);
            double entry = t.entryPrice().doubleValue();
            Double mark = m == null ? null : m.getValue();
            Double ret = mark == null ? null : mark / entry - 1;
            Double spyRet = m == null ? null : spy.ret(t.entryDate(), m.getKey(), false);
            open.add(new WeeklyOpen(t.id(), t.symbol(), t.entryDate(), entry, t.shares(), m == null ? null : m.getKey(), mark,
                    mark == null ? null : round2(t.shares() * (mark - entry)), ret, spyRet, ret == null || spyRet == null ? null : ret - spyRet,
                    t.targetHitDate() != null && !t.targetHitDate().isAfter(end) ? t.targetHitDate() : null));
        }
        LocalDate prevEnd = days.between(Market.US, start.minusDays(10), start.minusDays(1)).stream().reduce((a, b) -> b).orElse(null);
        Double spyWeek = prevEnd == null ? null : spy.closeToClose(prevEnd, end);
        Map.Entry<LocalDate, SignalBar> in = spy.bars().floorEntry(accountStart);
        Double spySince = in == null || !in.getKey().equals(accountStart) || spy.bars().floorEntry(end) == null ? null
                : spy.bars().floorEntry(end).getValue().close() / in.getValue().open() - 1;
        double[] universe = prevEnd == null ? new double[] {Double.NaN, 0} : universeReturn(prevEnd, end);
        Totals totals = totals(closedAll);
        return new WeeklyReport(rules.version(), start, end, accountStart, initial, round2(equityStart), round2(equityEnd),
                last.cash().doubleValue(), round2(equityEnd - equityStart), equityStart > 0 ? equityEnd / equityStart - 1 : null,
                equityEnd / initial - 1, round2(realizedWeek), round2(last.positionsValue().doubleValue() - costOpen), open.size(),
                spyWeek, spySince, Double.isNaN(universe[0]) ? null : universe[0], (int) universe[1],
                SimLedger.maxDrawdown(curve.stream().map(e -> e.equity().doubleValue()).toList()), closedWeek, totals, open,
                filled.stream().map(SimTradeRow::updatedThrough).filter(java.util.Objects::nonNull).max(LocalDate::compareTo).orElse(end),
                CAVEATS);
    }

    private static WeeklyTrade weeklyTrade(SimTradeRow t) {
        return new WeeklyTrade(t.id(), t.symbol(), t.entryDate(), t.entryPrice().doubleValue(), t.shares(), t.exitDate(),
                dbl(t.exitPrice()), t.exitReason(), dbl(t.pnl()), dbl(t.returnPct()), dbl(t.rMultiple()), dbl(t.spyReturn()),
                dbl(t.excessReturn()), t.shadowStatus(), dbl(t.shadowPnl()), dbl(t.shadowReturn()));
    }

    static Totals totals(List<SimTradeRow> closed) {
        int wins = 0;
        double pnl = 0;
        double sumRet = 0;
        double sumExcess = 0;
        int withExcess = 0;
        int manual = 0;
        double manualDiff = 0;
        int paired = 0;
        for (SimTradeRow t : closed) {
            double p = t.pnl().doubleValue();
            pnl += p;
            wins += p > 0 ? 1 : 0;
            sumRet += t.returnPct().doubleValue();
            if (t.excessReturn() != null) {
                sumExcess += t.excessReturn().doubleValue();
                withExcess++;
            }
            if ("TAKE_PROFIT".equals(t.exitReason()) || "MANUAL".equals(t.exitReason())) {
                manual++;
                // 影子还开着的不配对：它的结果还没定，拿浮动值比会把没定的当成定了的
                if ("CLOSED".equals(t.shadowStatus()) && t.shadowPnl() != null) {
                    manualDiff += p - t.shadowPnl().doubleValue();
                    paired++;
                }
            }
        }
        int n = closed.size();
        return new Totals(n, wins, n - wins, round2(pnl), n == 0 ? null : sumRet / n, withExcess == 0 ? null : sumExcess / withExcess,
                withExcess, manual, paired == 0 ? null : round2(manualDiff), paired);
    }

    /** 范围（量化选股的全部标的）等权的周收益：每只上周末收盘 → 本周末收盘（结构口径），两头都有 K 线的取平均。 */
    private double[] universeReturn(LocalDate prevEnd, LocalDate end) {
        List<InstrumentRow> all = targets.all();
        double sum = 0;
        int n = 0;
        for (int i = 0; i < all.size(); i += 50) {
            Map<Long, Instrument> chunk = new LinkedHashMap<>();
            all.subList(i, Math.min(all.size(), i + 50)).forEach(r -> chunk.put(r.id(), r.instrument()));
            Map<Long, List<DailyBar>> raw = bars.findMany(chunk, prevEnd, end);
            Map<Long, List<RehabFactor>> factors = rehabs.findMany(chunk);
            Set<LocalDate> calendar = new HashSet<>(days.between(Market.US, prevEnd, end));
            for (Long id : chunk.keySet()) {
                List<DailyBar> b = raw.getOrDefault(id, List.of());
                if (b.isEmpty()) {
                    continue;
                }
                try {
                    List<SignalBar> s = SignalTrades.scaledTo(b, factors.getOrDefault(id, List.of()), calendar, end, end);
                    if (s.size() >= 2 && s.getFirst().date().equals(prevEnd) && s.getLast().date().equals(end)) {
                        sum += s.getLast().close() / s.getFirst().close() - 1;
                        n++;
                    }
                } catch (IllegalStateException ex) {
                    // 结构口径算不出来的（缺比例）不进平均，也不猜
                }
            }
        }
        return new double[] {n == 0 ? Double.NaN : sum / n, n};
    }

    // ------------------------------------------------------------------ 审计

    /** 并进量化选股审计（check-daily 第六段）。有进行中的笔数时，「算到当天」是关键项；止盈提示是待处理的提示项。 */
    public List<BarAuditService.Check> auditChecks(LocalDate d) {
        List<SimTradeRow> active = sim.list(null).stream().filter(SimTradeRow::active).toList();
        List<String> behind = active.stream().filter(t -> !t.scoreDate().isAfter(d))
                .filter(t -> t.updatedThrough() == null || t.updatedThrough().isBefore(d))
                .map(t -> t.symbol() + " #" + t.id() + " 算到 " + (t.updatedThrough() == null ? "（从未）" : t.updatedThrough())).toList();
        List<BarAuditService.Check> out = new ArrayList<>();
        out.add(new BarAuditService.Check("simUpdated", behind.isEmpty(), !active.isEmpty(),
                active.isEmpty() ? "模拟池没有进行中的交易" : behind.isEmpty() ? "进行中 " + active.size() + " 笔都已算到 " + d
                        : behind.size() + " 笔没有算到 " + d, behind.size(), behind));
        List<String> alerts = active.stream().filter(t -> "OPEN".equals(t.status()) && t.targetHitDate() != null && t.closeRequestedAt() == null)
                .map(t -> t.symbol() + " #" + t.id() + " " + t.targetHitDate() + " 触及目标 " + fmt(t.target().doubleValue()) + "，最近收盘 "
                        + fmt(t.lastClose().doubleValue()) + "（" + t.lastCloseDate() + "）").toList();
        out.add(new BarAuditService.Check("simTargetHits", alerts.isEmpty(), false,
                alerts.isEmpty() ? "没有待处理的止盈提示" : alerts.size() + " 笔已触及止盈目标，等你决定是否平仓", alerts.size(), alerts));
        return out;
    }

    // ------------------------------------------------------------------ 工具

    private <T> T locked(Supplier<T> body) {
        try {
            if (!lock.tryLock(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("模拟交易正在重算，稍后再试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("被中断");
        }
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    private static SimTradeRow with(SimTradeRow t, String status, String cancelReason) {
        return new SimTradeRow(t.id(), t.rulesVersion(), t.instrumentId(), t.symbol(), t.modelVersion(), t.scoreDate(), t.scoreRank(),
                t.rating(), t.composite(), t.requestedAt(), t.setupClose(), t.setupAtr(), t.formulaStop(), t.stop(), t.stopLeg(),
                t.resistance(), t.plannedEntryDate(), status, t.entryDate(), t.entryPrice(), t.shares(), t.plusOneR(), t.target(),
                t.targetSource(), t.touchedPlusOneR(), t.targetHitDate(), t.activeStop(), t.chandelierActive(), t.closeRequestedAt(),
                t.plannedExitDate(), t.closeKind(), t.closeNote(), t.exitDate(), t.exitPrice(), t.exitReason(), t.pnl(), t.returnPct(),
                t.rMultiple(), t.mfeR(), t.maeR(), t.barsHeld(), t.lastClose(), t.lastCloseDate(), t.unrealizedPnl(), t.spyReturn(),
                t.excessReturn(), t.shadowStatus(), t.shadowExitDate(), t.shadowExitPrice(), t.shadowExitReason(), t.shadowPnl(),
                t.shadowReturn(), t.shadowLastClose(), cancelReason, t.updatedThrough(), t.updatedAt());
    }

    private static SimTradeRow through(SimTradeRow t, LocalDate through) {
        return new SimTradeRow(t.id(), t.rulesVersion(), t.instrumentId(), t.symbol(), t.modelVersion(), t.scoreDate(), t.scoreRank(),
                t.rating(), t.composite(), t.requestedAt(), t.setupClose(), t.setupAtr(), t.formulaStop(), t.stop(), t.stopLeg(),
                t.resistance(), t.plannedEntryDate(), t.status(), t.entryDate(), t.entryPrice(), t.shares(), t.plusOneR(), t.target(),
                t.targetSource(), t.touchedPlusOneR(), t.targetHitDate(), t.activeStop(), t.chandelierActive(), t.closeRequestedAt(),
                t.plannedExitDate(), t.closeKind(), t.closeNote(), t.exitDate(), t.exitPrice(), t.exitReason(), t.pnl(), t.returnPct(),
                t.rMultiple(), t.mfeR(), t.maeR(), t.barsHeld(), t.lastClose(), t.lastCloseDate(), t.unrealizedPnl(), t.spyReturn(),
                t.excessReturn(), t.shadowStatus(), t.shadowExitDate(), t.shadowExitPrice(), t.shadowExitReason(), t.shadowPnl(),
                t.shadowReturn(), t.shadowLastClose(), t.cancelReason(), through, t.updatedAt());
    }

    private static int count(List<SimTradeRow> all, String status) {
        return (int) all.stream().filter(t -> status.equals(t.status())).count();
    }

    private static String legText(SimSetup s) {
        return switch (s.leg()) {
            case ATR -> "收盘 − 2×ATR";
            case ZONE -> "支撑区底 − 0.5×ATR";
            case CAP -> "公式止损 " + fmt(s.formulaStop()) + " 距离超过 10%，封顶";
        };
    }

    private static String reasonText(String r) {
        return switch (r) {
            case "STOP" -> "收盘跌破止损，按收盘平仓";
            case "CHANDELIER" -> "收盘跌破吊灯止损，按收盘平仓";
            case "TAKE_PROFIT" -> "止盈请求，开盘平仓";
            case "MANUAL" -> "手动请求，开盘平仓";
            default -> r;
        };
    }

    private static Double dbl(BigDecimal x) {
        return x == null ? null : x.doubleValue();
    }

    static BigDecimal d6(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }

    static BigDecimal d2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static double round2(double v) {
        return d2(v).doubleValue();
    }

    private static String fmt(double x) {
        return BigDecimal.valueOf(x).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String fmt2(double x) {
        return d2(x).toPlainString();
    }

    private static String pct(BigDecimal ratio) {
        return ratio == null ? "—" : pct(ratio.doubleValue());
    }

    private static String pct(Double ratio) {
        return ratio == null ? "—" : BigDecimal.valueOf(ratio * 100).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
