package org.jdkxx.trader.core.account;

import org.jdkxx.trader.core.marketdata.Jobs;
import org.jdkxx.trader.core.marketdata.MarketDataFacade;
import org.jdkxx.trader.core.marketdata.PoolService;
import org.jdkxx.trader.core.marketdata.jobs.ScheduledSubmitter;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.gateway.AccountGateway;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.JobRunRepository;
import org.jdkxx.trader.storage.marketdata.PoolRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按盈透持仓维护池里的 HOLDING 角色（第 3 期步骤 3）。
 *
 * <p>规则：
 * <ul>
 *   <li>持有、池里没有 → 加为 HOLDING（来源 IBKR）；库里也没有的代码先向富途解析建档；</li>
 *   <li>持有、池里是 POOL → 升为 HOLDING，记下原角色，清仓后回到 POOL；</li>
 *   <li>持有、池里是 BENCHMARK → 不动：基准不因买卖改变（标普基准用的就是持仓里的 SPY，清仓就移出池会把基准弄丢）；</li>
 *   <li>池里是 HOLDING、已不持有 → 原角色是 POOL 的回 POOL，其余移出池（K 线与基本面数据保留）；</li>
 *   <li>现金管理工具与非美股持仓不算：不加入，池里若有 HOLDING 也按不持有处理。</li>
 * </ul>
 *
 * <p>保护：取持仓失败什么都不改；盈透返回的持仓一条都没有（含现金管理工具）而池里还有 HOLDING 时不清空（疑为数据不完整），
 * 只剩现金管理工具或非美股时照常按清仓处理；
 * 每一处改动都带状态条件（如"还是 POOL 才升"），池在此期间被别处改过就跳过并报出来。
 * 新增或升级的标的由 {@link ScheduledSubmitter} 排深度回补（快照作业里调用时作业线程被占，会延后重试）。
 */
public class HoldingSyncService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HoldingSyncService.class);
    static final String NOTE = "盈透持仓自动维护";

    public enum Action {
        /** 加为 HOLDING。 */
        ADD,
        /** POOL 升为 HOLDING。 */
        PROMOTE,
        /** 清仓，回到 POOL。 */
        RETURN_TO_POOL,
        /** 清仓，移出池。 */
        REMOVE
    }

    /** 一只持有的美股；instrumentId 为空表示库里没有。 */
    public record Held(Long instrumentId, String symbol) {
    }

    public record Change(Action action, String symbol, Long instrumentId) {
    }

    /**
     * @param untouched 持有但按规则不动的（如基准）
     * @param blocked   不执行的原因；为空表示可以执行
     */
    public record Plan(List<Change> changes, List<String> untouched, String blocked) {
    }

    public record Result(boolean applied, Plan plan, List<String> errors, String summary) {
    }

    private final AccountProperties props;
    private final AccountPositions source;
    private final InstrumentRepository instruments;
    private final PoolRepository pool;
    private final PoolService poolService;
    private final MarketDataFacade marketData;
    private final ScheduledSubmitter submitter;

    public HoldingSyncService(AccountProperties props, String configuredAccount, BrokerGateway broker, AccountGateway accounts,
                              InstrumentRepository instruments, PoolRepository pool, PoolService poolService,
                              MarketDataFacade marketData, JobRunRepository jobRuns) {
        this.props = props;
        this.source = new AccountPositions(configuredAccount, broker, accounts, instruments);
        this.instruments = instruments;
        this.pool = pool;
        this.poolService = poolService;
        this.marketData = marketData;
        this.submitter = new ScheduledSubmitter(jobRuns, "holding-sync-retry");
    }

    /**
     * 现取盈透持仓再同步。apply=false 只返回计划，不改池（映射时仍会顺手记下标的的盈透 conId）。
     *
     * @param trigger 深度回补作业的触发方式：MANUAL / SCHEDULE
     */
    public synchronized Result syncNow(boolean apply, String trigger) throws Exception {
        String accountId = source.chooseAccount();
        Set<String> cash = cashEquivalents();
        List<Held> held = new ArrayList<>();
        boolean brokerReturnedNothing = true;
        for (Position p : source.positions(accountId)) {
            if (p.quantity().signum() != 0) {
                brokerReturnedNothing = false;
            }
            if (p.quantity().signum() == 0 || !AccountPositions.stockUsd(p)) {
                continue;
            }
            String symbol = AccountPositions.normalize(p.symbol());
            if (!cash.contains(symbol)) {
                held.add(new Held(source.instrumentId(p), symbol));
            }
        }
        return run(held, brokerReturnedNothing, apply, trigger);
    }

    /** 快照作业里调用：用已经取到并映射好的持仓，直接应用。 */
    public synchronized Result syncFromSnapshot(List<ValuedPosition> valued) {
        List<Held> held = valued.stream()
                .filter(v -> v.stock() && !v.cashEquivalent() && "USD".equals(v.position().currency()))
                .map(v -> new Held(v.instrumentId(), AccountPositions.normalize(v.symbol())))
                .toList();
        boolean brokerReturnedNothing = valued.stream().noneMatch(v -> v.position().quantity().signum() != 0);
        return run(held, brokerReturnedNothing, true, "SCHEDULE");
    }

    /**
     * @param brokerReturnedNothing 盈透返回的持仓（过滤现金管理工具与非美股之前）一条都没有
     */
    Result run(List<Held> held, boolean brokerReturnedNothing, boolean apply, String trigger) {
        List<PoolRepository.SyncRow> members = pool.findAllForSync();
        Map<Long, String> symbols = new HashMap<>();
        instruments.findByIds(members.stream().map(PoolRepository.SyncRow::instrumentId).toList())
                .forEach(r -> symbols.put(r.id(), r.symbol()));
        Plan plan = plan(held, brokerReturnedNothing, members, symbols);
        if (plan.blocked() != null) {
            log.warn("持仓同步未执行：{}", plan.blocked());
        }
        if (!apply || plan.blocked() != null || plan.changes().isEmpty()) {
            return new Result(false, plan, List.of(), describe(plan, false, List.of()));
        }

        List<String> errors = new ArrayList<>();
        int done = 0;
        boolean needBackfill = false;
        for (Change c : plan.changes()) {
            try {
                boolean ok = switch (c.action()) {
                    case ADD -> add(c);
                    case PROMOTE -> pool.promoteToHolding(c.instrumentId());
                    case RETURN_TO_POOL -> pool.returnToPool(c.instrumentId());
                    case REMOVE -> pool.deleteHolding(c.instrumentId());
                };
                if (ok) {
                    done++;
                    needBackfill |= c.action() == Action.ADD || c.action() == Action.PROMOTE;
                    log.info("持仓同步：{} {}", label(c.action()), c.symbol());
                } else {
                    errors.add(c.symbol() + "：池里状态已被别处改动，未执行" + label(c.action()));
                }
            } catch (RuntimeException e) {
                errors.add(c.symbol() + "：" + e.getMessage());
                log.warn("持仓同步 {} {} 失败：{}", label(c.action()), c.symbol(), e.toString());
            }
        }
        if (done > 0) {
            poolService.notifyChanged();
            if (needBackfill) {
                submitter.submit("持仓新增标的的深度回补", Jobs.DEEP_BACKFILL, () -> marketData.backfillPending(trigger));
            }
        }
        return new Result(true, plan, List.copyOf(errors), describe(plan, true, errors));
    }

    private boolean add(Change c) {
        if (c.instrumentId() != null) {
            return pool.addHolding(c.instrumentId(), NOTE);
        }
        PoolService.AddResult r = poolService.add(c.symbol(), PoolRole.HOLDING, NOTE);
        pool.markSource(r.member().instrumentId(), "IBKR");
        return true;
    }

    /** held 就是盈透返回的全部持仓时用（测试）。 */
    static Plan plan(List<Held> held, List<PoolRepository.SyncRow> members, Map<Long, String> symbols) {
        return plan(held, held.isEmpty(), members, symbols);
    }

    /**
     * "持仓为空就不清空"只看盈透<b>原始</b>返回：2.0.2 前看的是过滤掉现金管理工具之后的列表，
     * 清仓后只剩 SGOV 时被当成数据不完整拦下，HOLDING 永远清不掉、每天快照都 PARTIAL。
     */
    static Plan plan(List<Held> held, boolean brokerReturnedNothing, List<PoolRepository.SyncRow> members, Map<Long, String> symbols) {
        Map<Long, PoolRepository.SyncRow> byId = new HashMap<>();
        members.forEach(m -> byId.put(m.instrumentId(), m));
        long holdingNow = members.stream().filter(m -> m.role() == PoolRole.HOLDING).count();
        if (brokerReturnedNothing && holdingNow > 0) {
            return new Plan(List.of(), List.of(),
                    "盈透返回的持仓为空，而池里还有 " + holdingNow + " 只 HOLDING，疑为数据不完整，不自动清空");
        }

        List<Change> changes = new ArrayList<>();
        List<String> untouched = new ArrayList<>();
        Set<Long> heldIds = new HashSet<>();
        Set<String> unmapped = new HashSet<>();
        for (Held h : held) {
            if (h.instrumentId() == null) {
                if (unmapped.add(h.symbol())) {
                    changes.add(new Change(Action.ADD, h.symbol(), null));
                }
                continue;
            }
            if (!heldIds.add(h.instrumentId())) {
                continue;
            }
            PoolRepository.SyncRow m = byId.get(h.instrumentId());
            if (m == null) {
                changes.add(new Change(Action.ADD, h.symbol(), h.instrumentId()));
            } else if (m.role() == PoolRole.POOL) {
                changes.add(new Change(Action.PROMOTE, h.symbol(), h.instrumentId()));
            } else if (m.role() == PoolRole.BENCHMARK) {
                untouched.add(h.symbol() + "（基准，不改角色）");
            }
        }
        for (PoolRepository.SyncRow m : members) {
            if (m.role() != PoolRole.HOLDING || heldIds.contains(m.instrumentId())) {
                continue;
            }
            String symbol = symbols.getOrDefault(m.instrumentId(), "#" + m.instrumentId());
            changes.add(new Change("POOL".equals(m.returnRole()) ? Action.RETURN_TO_POOL : Action.REMOVE, symbol, m.instrumentId()));
        }
        return new Plan(List.copyOf(changes), List.copyOf(untouched), null);
    }

    static String describe(Plan plan, boolean applied, List<String> errors) {
        if (plan.blocked() != null) {
            return "未执行：" + plan.blocked();
        }
        if (plan.changes().isEmpty()) {
            return "无需变动";
        }
        String list = plan.changes().stream().map(c -> label(c.action()) + " " + c.symbol()).collect(Collectors.joining("、"));
        return (applied ? "已执行 " : "计划 ") + list
                + (errors.isEmpty() ? "" : "；失败 " + errors.size() + " 处：" + String.join("；", errors));
    }

    private static String label(Action a) {
        return switch (a) {
            case ADD -> "加入 HOLDING";
            case PROMOTE -> "POOL→HOLDING";
            case RETURN_TO_POOL -> "HOLDING→POOL";
            case REMOVE -> "移出池";
        };
    }

    private Set<String> cashEquivalents() {
        return props.cashEquivalents().stream().map(AccountPositions::normalize).collect(Collectors.toSet());
    }

    @Override
    public void close() {
        submitter.close();
    }
}
