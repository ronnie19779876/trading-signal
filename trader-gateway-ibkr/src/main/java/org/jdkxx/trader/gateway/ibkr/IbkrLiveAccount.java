package org.jdkxx.trader.gateway.ibkr;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.PositionPnl;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.LiveAccountListener;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 一个账户的实时订阅（3.0.2 起）：常驻账户汇总、账户盈亏、持仓，加上每只持仓一个逐只盈亏与一条行情（取最新价）。
 *
 * <p><b>所有方法都在 dispatch 线程上执行</b>（{@link IbkrGateway} 负责转过来，订阅回调也由 {@link IbkrSubscriptions}
 * 转到 dispatch），所以状态不加锁。
 *
 * <p>实测依据（2026-09-19 只读探针）：
 * <ul>
 *   <li>账户汇总首批约 0.3 秒回齐，之后<b>逐条</b>推送、不再有 End，约 3 分钟一批 → 逐条攒起来、静默 {@value #SUMMARY_DEBOUNCE_MS} ms 后发一次；</li>
 *   <li>reqPnL 的推送<b>对不对不能按第几条判断</b>（09-19 三次实测）：刚连上时首条只含一只持仓、1 秒后第二条才对；
 *       逐只盈亏订上十几秒内退订再订，首条也是错的；逐只盈亏稳定运行后再订，<b>只推一条而且是对的</b>，价格静止时不再有第二条。
 *       对的推送与逐只当日 / 浮盈之和精确相等，错的差得很远 → <b>用逐只之和核对</b>：对得上才采用（只用来判断完整与否，显示的仍是 reqPnL 原值），
 *       采用过一次后后续推送直接放行；逐只还没到齐的先挂起，到齐再核。{@value #PNL_WATCHDOG_SECONDS} 秒还没有通过核对的就重订，
 *       最多 {@value #PNL_MAX_RETRIES} 次；</li>
 *   <li>持仓标的行情（reqMktData）取 lastPrice 与前收（CLOSE）：账户有实时权限；收盘后买卖价为 -1；最新价不等于"市值 ÷ 数量"（GOOG 346.08 对 344.41）；
 *       与盈透 App 截图对照（09-19）：最新价 346.08、前收 343.68 一致；</li>
 *   <li>持仓 End 之后成交会再推变动行，数量为 0 表示清仓 → 维护完整列表，每次变动发一次全量，并同步逐只盈亏的订阅。</li>
 * </ul>
 * 1101（连接恢复、订阅数据丢失）时会话不变，旧订阅还挂在网关上：先取消再重订，否则账户汇总会撞上每客户端 2 个的上限（322）。
 * <b>账户汇总不在这里</b>：它改成了连接周期内只订一次的常驻订阅（{@link IbkrAccountSummaryFeed}，3.0.9），
 * 实时账户与每日快照共用那一条。原因见该类的注释——盈透的名额不随取消释放，订了又退迟早撞 322。
 *
 * <p>账户盈亏的核对口径演变：3.0.10 扣已实现（被 10-09 证伪）→ 3.1.5 平仓日只比浮盈 → 3.4.6 没有平仓时只比当日，见 {@link #verifyPnl()}。
 * <p><b>持仓均价与逐只浮盈</b>（3.4.6）：有账户更新推送（{@link IbkrPortfolioFeed}，只在生产实例）且数量一致时取它的值，
 * 与盈透 App 同源；当日、市值仍取 reqPnLSingle，现价仍取行情。
 */
final class IbkrLiveAccount {

    private static final Logger log = LoggerFactory.getLogger(IbkrLiveAccount.class);
    static final long PNL_WATCHDOG_SECONDS = 10;
    static final int PNL_MAX_RETRIES = 3;
    /** 核对容差：对的推送实测与逐只之和精确相等，错的差几十到上千；留一点余量给盘中两边不在同一时刻的抖动。 */
    static final double PNL_TOLERANCE = 1.0;

    /** 与连接之间的接缝：生产用 {@link IbkrConnection}，单测用替身。 */
    interface Wire {
        Object sessionToken();

        int nextId();

        boolean subscribe(String what, Consumer<EClientSocket> request);

        void unsubscribe(String what, Consumer<EClientSocket> cancel);

        void open(int id, IbkrSubscriptions.Handler handler);

        void close(int id);

        /** 延时任务：到点在 dispatch 线程上执行。 */
        Runnable later(Duration delay, Runnable task);

        Instant now();
    }

    private record Sub(int id, String what, Consumer<EClientSocket> cancel) {
    }

    final String accountId;
    private LiveAccountListener listener;
    private final Wire wire;
    /** 账户更新推送（3.4.6，只在生产实例有）：持仓均价与浮盈优先取它；null 或没有对应行时用原来的来源。 */
    private IbkrPortfolioFeed portfolio;

    /** 订上时所在的会话（{@link Wire#sessionToken()}）；null = 当前没有订阅 */
    private Object bound;
    private Sub positionsSub;
    private Sub pnlSub;
    private final Map<String, Sub> singles = new LinkedHashMap<>();
    private final Map<String, Sub> quotes = new LinkedHashMap<>();
    private final Map<String, Boolean> delayed = new LinkedHashMap<>();
    /** 每只持仓最近收到的最新价与前收：两者分两条 tick 到，合在一起发。 */
    private final Map<String, java.math.BigDecimal[]> lastAndClose = new LinkedHashMap<>();

    private final Map<String, IbkrAccounts.PositionRow> positions = new LinkedHashMap<>();
    private boolean positionsReady;
    private boolean pnlValid;
    /** 还没通过核对的最近一条账户盈亏推送 */
    private IbkrAccounts.PnlRow pnlPending;
    /** 每只持仓最近一次逐只盈亏：核对账户盈亏用 */
    private final Map<String, IbkrAccounts.PnlSingleRow> singleRows = new LinkedHashMap<>();
    private int pnlRetries;
    private Runnable pnlWatchdog;
    /** 核对不通过的日志每次订阅只记一条，免得 5 秒一推刷屏。 */
    private boolean pnlMismatchLogged;
    private boolean pnlDailySkipLogged;

    IbkrLiveAccount(String accountId, LiveAccountListener listener, Wire wire) {
        this.accountId = accountId;
        this.listener = listener;
        this.wire = wire;
    }

    void listener(LiveAccountListener listener) {
        this.listener = listener;
    }

    void portfolio(IbkrPortfolioFeed portfolio) {
        this.portfolio = portfolio;
    }

    /**
     * 账户更新推送有新一批生效：持仓（均价）与逐只盈亏（浮盈）按新值重发一次。
     * 推送约 3 分钟一批，加仓后页面上的成本最多晚这么久更新。
     */
    void onPortfolioChanged() {
        if (bound == null || !positionsReady) {
            return;
        }
        emitPositions();
        for (Map.Entry<String, IbkrAccounts.PnlSingleRow> e : singleRows.entrySet()) {
            listener.onPositionPnl(positionPnl(e.getKey(), e.getValue()));
        }
    }

    private Map<String, IbkrAccounts.PortfolioRow> portfolioRows() {
        return portfolio == null ? Map.of() : portfolio.rows();
    }

    /** 均价换成账户更新推送的值（数量一致时），否则原样。 */
    private List<Position> withPortfolioCost(List<Position> list) {
        Map<String, IbkrAccounts.PortfolioRow> rows = portfolioRows();
        if (rows.isEmpty()) {
            return list;
        }
        List<Position> out = new ArrayList<>(list.size());
        for (Position p : list) {
            java.math.BigDecimal cost = IbkrPortfolioFeed.cost(rows, p.brokerRef(), p.quantity(), p.averageCost(),
                    p.symbol(), portfolio.costDiffLogged());
            out.add(cost == null ? p : new Position(p.broker(), p.accountId(), p.brokerRef(), p.symbol(), p.localSymbol(),
                    p.securityType(), p.exchange(), p.primaryExchange(), p.currency(), p.quantity(), cost));
        }
        return List.copyOf(out);
    }

    /** 逐只盈亏：浮盈换成账户更新推送的值（数量一致时）；当日与市值仍是 reqPnLSingle 的原值。 */
    private PositionPnl positionPnl(String conId, IbkrAccounts.PnlSingleRow row) {
        PositionPnl raw = IbkrAccounts.positionPnl(conId, row, wire.now());
        java.math.BigDecimal unrealized = IbkrPortfolioFeed.unrealized(portfolioRows(), conId, raw.quantity());
        return unrealized == null ? raw : new PositionPnl(raw.broker(), raw.brokerRef(), raw.receivedAt(), raw.quantity(),
                raw.daily(), unrealized, raw.realized(), raw.marketValue());
    }

    boolean subscribed() {
        return bound != null;
    }

    /** 订阅（或重订）。未连接时什么也不做，返回 false，等连上后再调。 */
    boolean subscribe() {
        Object current = wire.sessionToken();
        if (bound != null && bound == current) {
            cancelAll();        // 同一会话（1101）：旧订阅还活着，先退
        } else {
            forgetAll();        // 旧会话已结束：订阅随之失效，只丢掉记录
        }
        bound = current;
        positionsReady = false;
        pnlValid = false;
        pnlPending = null;
        pnlRetries = 0;

        positionsSub = open("实时持仓", positionsHandler(),
                id -> c -> c.reqPositionsMulti(id, accountId, ""), id -> c -> c.cancelPositionsMulti(id));
        pnlSub = openPnl();
        if (positionsSub == null || pnlSub == null) {
            cancelAll();        // 发一半就断了：退掉已发的，等下一次连上整组重订
            bound = null;
            return false;
        }
        schedulePnlWatchdog();
        return true;
    }

    private Sub openPnl() {
        pnlPending = null;
        return open("实时账户盈亏", pnlHandler(), id -> c -> c.reqPnL(id, accountId, ""), id -> c -> c.cancelPnL(id));
    }

    private void schedulePnlWatchdog() {
        pnlWatchdog = wire.later(Duration.ofSeconds(PNL_WATCHDOG_SECONDS), this::checkPnl);
    }

    /** 账户盈亏迟迟没有有效值（首条已丢、第二条不来）：退订再订，最多 {@value #PNL_MAX_RETRIES} 次。 */
    void checkPnl() {
        pnlWatchdog = null;
        if (bound == null || pnlValid || pnlRetries >= PNL_MAX_RETRIES) {
            return;
        }
        pnlRetries++;
        log.info("实时账户盈亏 {} 秒没有有效推送，重订（第 {} 次）", PNL_WATCHDOG_SECONDS, pnlRetries);
        cancel(pnlSub);
        pnlSub = openPnl();
        if (pnlSub != null && pnlRetries < PNL_MAX_RETRIES) {
            schedulePnlWatchdog();
        }
    }

    /** 退订全部。 */
    void stop() {
        if (bound != null && bound == wire.sessionToken()) {
            cancelAll();
        } else {
            forgetAll();
        }
        bound = null;
    }

    int singleCount() {
        return singles.size();
    }

    int quoteCount() {
        return quotes.size();
    }

    private Sub open(String what, IbkrSubscriptions.Handler handler,
                     java.util.function.IntFunction<Consumer<EClientSocket>> request,
                     java.util.function.IntFunction<Consumer<EClientSocket>> cancel) {
        int id = wire.nextId();
        wire.open(id, handler);
        if (!wire.subscribe(what, request.apply(id))) {
            wire.close(id);
            return null;
        }
        return new Sub(id, what, cancel.apply(id));
    }

    private void cancel(Sub sub) {
        if (sub != null) {
            wire.close(sub.id());
            wire.unsubscribe(sub.what(), sub.cancel());
        }
    }

    private void cancelAll() {
        cancel(positionsSub);
        cancel(pnlSub);
        singles.values().forEach(this::cancel);
        quotes.values().forEach(this::cancel);
        clearState();
    }

    private void forgetAll() {
        for (Sub s : allSubs()) {
            wire.close(s.id());
        }
        clearState();
    }

    private List<Sub> allSubs() {
        List<Sub> all = new ArrayList<>(singles.values());
        all.addAll(quotes.values());
        for (Sub s : new Sub[] {positionsSub, pnlSub}) {
            if (s != null) {
                all.add(s);
            }
        }
        return all;
    }

    private void clearState() {
        positionsSub = pnlSub = null;
        singles.clear();
        quotes.clear();
        delayed.clear();
        lastAndClose.clear();
        singleRows.clear();
        pnlPending = null;
        pnlMismatchLogged = false;
        pnlDailySkipLogged = false;
        if (pnlWatchdog != null) {
            pnlWatchdog.run();   // 取消
            pnlWatchdog = null;
        }
        positions.clear();
        positionsReady = false;
    }

    // ------------------------------------------------------------------ 持仓

    private IbkrSubscriptions.Handler positionsHandler() {
        return new IbkrSubscriptions.Handler() {
            @Override
            public void item(Object item) {
                IbkrAccounts.PositionRow r = (IbkrAccounts.PositionRow) item;
                if (r.contract() == null || !accountId.equals(r.account())) {
                    return;
                }
                String conId = Integer.toString(r.contract().conid());
                if (r.position() == null || !r.position().isValid()) {
                    log.warn("实时持仓收到无效数量（{}），忽略这一条", r.contract().symbol());
                    return;
                }
                if (r.position().value().signum() == 0) {
                    positions.remove(conId);   // 清仓
                } else {
                    positions.put(conId, r);
                }
                if (positionsReady) {
                    emitPositions();
                }
            }

            @Override
            public void end() {
                positionsReady = true;
                emitPositions();
            }

            @Override
            public void error(RequestRejectedException e) {
                listener.onLiveError("实时持仓", e);
            }
        };
    }

    private void emitPositions() {
        try {
            List<Position> list = withPortfolioCost(IbkrAccounts.positions(accountId, new ArrayList<>(positions.values())));
            listener.onPositions(list);
        } catch (RuntimeException e) {
            log.warn("实时持仓映射失败：{}", e.toString());
        }
        syncSingles();
    }

    /** 逐只盈亏与行情的订阅跟着持仓走：新出现的订、清仓的退。 */
    private void syncSingles() {
        for (String conId : new ArrayList<>(singles.keySet())) {
            if (!positions.containsKey(conId)) {
                cancel(singles.remove(conId));
                singleRows.remove(conId);
            }
        }
        for (String conId : new ArrayList<>(quotes.keySet())) {
            if (!positions.containsKey(conId)) {
                cancel(quotes.remove(conId));
                delayed.remove(conId);
                lastAndClose.remove(conId);
            }
        }
        for (Map.Entry<String, IbkrAccounts.PositionRow> e : positions.entrySet()) {
            String conId = e.getKey();
            int con = Integer.parseInt(conId);
            if (!singles.containsKey(conId)) {
                Sub s = open("逐只盈亏 " + conId, singleHandler(conId),
                        id -> c -> c.reqPnLSingle(id, accountId, "", con), id -> c -> c.cancelPnLSingle(id));
                if (s != null) {
                    singles.put(conId, s);
                }
            }
            if (!quotes.containsKey(conId)) {
                Contract src = e.getValue().contract();
                Contract contract = new Contract();
                contract.conid(con);
                contract.exchange("SMART");
                contract.secType(src.getSecType());
                contract.currency(src.currency());
                Sub q = open("持仓行情 " + conId, quoteHandler(conId),
                        id -> c -> c.reqMktData(id, contract, "", false, false, null), id -> c -> c.cancelMktData(id));
                if (q != null) {
                    quotes.put(conId, q);
                }
            }
        }
    }

    private IbkrSubscriptions.Handler quoteHandler(String conId) {
        return new IbkrSubscriptions.Handler() {
            @Override
            public void item(Object item) {
                if (item instanceof IbkrAccounts.MarketDataTypeRow t) {
                    delayed.put(conId, t.type() == 3 || t.type() == 4);
                } else if (item instanceof IbkrAccounts.TickRow t) {
                    boolean last = IbkrAccounts.isLast(t.field());
                    if (!last && !IbkrAccounts.isClose(t.field())) {
                        return;   // 买卖价、开高低等不关心
                    }
                    java.math.BigDecimal price = IbkrAccounts.price(t.price());
                    if (price == null) {
                        return;
                    }
                    java.math.BigDecimal[] lc = lastAndClose.computeIfAbsent(conId, k -> new java.math.BigDecimal[2]);
                    lc[last ? 0 : 1] = price;
                    boolean isDelayed = delayed.getOrDefault(conId, false)
                            || t.field() == IbkrAccounts.TICK_DELAYED_LAST || t.field() == IbkrAccounts.TICK_DELAYED_CLOSE;
                    listener.onPositionPrice(new org.jdkxx.trader.domain.PositionPrice(org.jdkxx.trader.domain.Broker.IBKR,
                            conId, wire.now(), lc[0], lc[1], isDelayed));
                }
            }

            @Override
            public void error(RequestRejectedException e) {
                listener.onLiveError("持仓行情", e);
            }
        };
    }

    private IbkrSubscriptions.Handler singleHandler(String conId) {
        return new IbkrSubscriptions.Handler() {
            @Override
            public void item(Object item) {
                IbkrAccounts.PnlSingleRow row = (IbkrAccounts.PnlSingleRow) item;
                singleRows.put(conId, row);
                listener.onPositionPnl(positionPnl(conId, row));
                if (!pnlValid && pnlPending != null) {
                    verifyPnl();   // 账户盈亏先到、逐只后到：到齐了再核
                }
            }

            @Override
            public void error(RequestRejectedException e) {
                listener.onLiveError("逐只盈亏", e);
            }
        };
    }

    // ------------------------------------------------------------------ 账户汇总

    // ------------------------------------------------------------------ 账户盈亏

    private IbkrSubscriptions.Handler pnlHandler() {
        return new IbkrSubscriptions.Handler() {
            @Override
            public void item(Object item) {
                IbkrAccounts.PnlRow row = (IbkrAccounts.PnlRow) item;
                if (pnlValid) {
                    listener.onPnl(IbkrAccounts.pnl(row, wire.now()));
                    return;
                }
                pnlPending = row;
                verifyPnl();
            }

            @Override
            public void error(RequestRejectedException e) {
                listener.onLiveError("实时账户盈亏", e);
            }
        };
    }

    /**
     * 核对挂起的账户盈亏：逐只盈亏要覆盖全部持仓；<b>当天没有平仓时比当日之和，有平仓时比浮盈之和</b>
     * （容差 {@value #PNL_TOLERANCE}，3.4.6 起）。逐只之和只用来判断这条推送完整与否，发出去的仍是账户盈亏的原值。
     *
     * <h2>为什么没有平仓时不再比浮盈（2026-10-20 ~ 10-22 实测）</h2>
     * 当天加仓 GOOG 100 股后，{@code reqPnLSingle} 的浮盈仍按加仓前的均价 346.806 算（App 与账户更新推送是 339.525），
     * 账户口径与逐只之和的浮盈恒差 1,179.65（= 100 × 11.80），账户盈亏因此整天置空；网关次日每日重启后仍然如此。
     * 而当日两边精确相等（差 0.0），首条推送不完整时当日照样对不上（10-22 实测差 48.74），把关能力不减。
     * 这里核对用的仍是 {@code reqPnLSingle} 的原值（两边都是流式推送，能精确比）；页面显示的逐只浮盈另取账户更新推送。
     *
     * <h2>为什么当日在平仓日不能比（2026-10-09 实测，推翻了 3.0.10 的做法）</h2>
     * 当天卖掉 BRK.B 80 股之后的真实推送：
     * <pre>
     * 账户   当日=1093.3018226278286  浮盈=26615.850201798563  已实现=477.157432
     * 逐只之和 当日=1099.14422262783    浮盈=26615.850201798563
     * </pre>
     * <ul>
     *   <li><b>浮盈差 = 0.0</b>：两边口径都只含在持，平仓日照样精确对得上——它才是完整性的干净判据；</li>
     *   <li><b>当日差 = −5.8424</b>，而 {@code realized = 477.157432}。3.0.10 扣掉 realized 之后差变成
     *       <b>−483.00</b>，<b>方向反了、量级放大 82 倍</b>；</li>
     *   <li>账户当日含的是已平仓标的的<b>当日变动</b>（卖出价相对前收，含手续费），<b>不是</b>它相对成本的终身已实现。
     *       佐证：BRK.B 前收市值 80 × 511.05 = 40,884.00，卖出到账现金 40,878.16，差 <b>5.84</b>；
     *       相隔 40 分钟的两条日志差值是完全相同的 −482.99983200000133（固定量，不是时间漂移）。</li>
     * </ul>
     * <b>正确的调整量我们拿不到</b>：标的一平仓就从 {@code reqPositionsMulti} 消失，
     * 而 {@code reqPnLSingle} 的 realized 恒为 {@code Double.MAX_VALUE}。扩大容差也不行——
     * 平仓标的的当日变动无界（今天只有 5.84 是因为几乎平价卖出）。所以平仓日<b>不比当日</b>，
     * 改由浮盈单独把关；当日仍照常显示盈透原值。
     *
     * <p><b>n = 1</b>：一个交易日、一只平仓标的、两次采样。等下一次平仓日复核。
     */
    private void verifyPnl() {
        IbkrAccounts.PnlRow row = pnlPending;
        if (row == null || !positionsReady || !singleRows.keySet().containsAll(positions.keySet())) {
            return;
        }
        double daily = 0;
        double unreal = 0;
        for (String conId : positions.keySet()) {
            IbkrAccounts.PnlSingleRow r = singleRows.get(conId);
            if (IbkrAccounts.amount(r.daily()) == null || IbkrAccounts.amount(r.unrealized()) == null) {
                return;
            }
            daily += r.daily();
            unreal += r.unrealized();
        }
        // 当天有没有平仓：账户级 realized 非 0 即有（逐只的 realized 恒为 Double.MAX_VALUE，不可用）。
        // realized 不可用时判断不了，按「有平仓」处理——只比浮盈，宁可少一道也不要在平仓日把当日盈亏整天挂空。
        java.math.BigDecimal realizedAmount = IbkrAccounts.amount(row.realized());
        Double realized = realizedAmount == null ? null : realizedAmount.doubleValue();
        boolean closedToday = realized == null || realized != 0;

        // 没有平仓：只比当日（3.4.6）。加仓日 reqPnLSingle 的浮盈按加仓前的成本算、和账户口径差一个固定量
        // （2026-10-20 GOOG 恒差 1,179.65，网关次日重启也没改对），而当日两边精确相等（差 0.0）；
        // 首条推送不完整时当日照样对不上（10-22 实测差 48.74），把关能力不减。
        // 平仓日：当日不可比（见上），只能比浮盈——同一天又有加仓且成本滞后时会判不通过，照实记日志。
        boolean unrealOk = !closedToday || Math.abs(row.unrealized() - unreal) <= PNL_TOLERANCE;
        boolean dailyOk = closedToday || Math.abs(row.daily() - daily) <= PNL_TOLERANCE;

        if (unrealOk && dailyOk) {
            pnlValid = true;
            pnlPending = null;
            pnlMismatchLogged = false;
            if (closedToday) {
                logDailyNotChecked(row, realized, daily);
            }
            listener.onPnl(IbkrAccounts.pnl(row, wire.now()));
            return;
        }
        logPnlMismatch(row, realized, daily, unreal, closedToday);
    }

    /**
     * 平仓日跳过当日比较时记一条（每次订阅最多一条）。
     *
     * <p>把两个当日与 realized 都打出来，是为了积累「平仓日账户当日与逐只之和差多少」的实测样本：
     * 2026-10-09 差 −5.84，若将来某天差到几百上千，就说明这条口径还有别的成分，到时有据可查。
     * 顺带也能看出 realized 在正常日是不是真的给 0（若常年「未设」，这条检查等于每天都被跳过）。
     */
    private void logDailyNotChecked(IbkrAccounts.PnlRow row, Double realized, double singlesDaily) {
        if (pnlDailySkipLogged) {
            return;
        }
        pnlDailySkipLogged = true;
        log.info("当天有平仓（已实现={}），当日盈亏不与逐只之和比对、只用浮盈把关：账户当日={} 逐只之和当日={} 差={}",
                realized == null ? "未设" : realized, row.daily(), singlesDaily, row.daily() - singlesDaily);
    }

    /**
     * 核对不通过时把数字记下来，每次订阅最多一条。
     *
     * <p>2026-09-23 生产上当日盈亏整天空着，日志里只有"没有有效推送，重订第 N 次"，<b>一个数字都没有</b>，
     * 事后只能靠净值反推去猜盈透的口径。这条日志就是为了让下一次失败留下证据而不是沉默。
     */
    private void logPnlMismatch(IbkrAccounts.PnlRow row, Double realized, double singlesDaily, double singlesUnrealized,
                                boolean closedToday) {
        if (pnlMismatchLogged) {
            return;
        }
        pnlMismatchLogged = true;
        log.warn("实时账户盈亏核对不通过（{} 只持仓）：账户 当日={} 浮盈={} 已实现={}；逐只之和 当日={} 浮盈={}；"
                        + "当日差={}（{}）浮盈差={}（容差 {}）",
                positions.size(), row.daily(), row.unrealized(), realized == null ? "未设" : realized,
                singlesDaily, singlesUnrealized,
                row.daily() - singlesDaily, closedToday ? "当天有平仓，未参与判定" : "参与判定",
                row.unrealized() - singlesUnrealized, PNL_TOLERANCE);
    }

    @Override
    public String toString() {
        return "IbkrLiveAccount[**** subscribed=" + subscribed() + " positions=" + positions.size() + "]";
    }
}
