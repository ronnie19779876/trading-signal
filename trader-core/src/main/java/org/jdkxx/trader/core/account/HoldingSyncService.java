package org.jdkxx.trader.core.account;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

/**
 * Maintains the HOLDING role in the pool from IBKR positions (phase 3 step 3).
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>held, not in the pool -&gt; added as HOLDING (source IBKR); a symbol not even in the
 *       database is first resolved via Futu and created;
 *   <li>held, POOL in the pool -&gt; promoted to HOLDING with the original role recorded, returns
 *       to POOL once the position is closed;
 *   <li>held, BENCHMARK in the pool -&gt; untouched: a benchmark does not change with trading (the
 *       S&amp;P benchmark is the SPY in the positions, and removing it from the pool on a full sale
 *       would lose the benchmark);
 *   <li>HOLDING in the pool, no longer held -&gt; back to POOL if the original role was POOL,
 *       otherwise removed from the pool (bar and fundamentals data are kept);
 *   <li>cash-management instruments and non-US-stock positions do not count: they are not added,
 *       and a HOLDING for them in the pool is treated as not held.
 * </ul>
 *
 * <p>Safeguards: if fetching positions fails nothing changes. If IBKR returns no positions at all
 * (cash-management instruments included) while the pool still has HOLDING, nothing is cleared
 * (suspected incomplete data); if only cash-management instruments or non-US stocks remain, it is
 * handled as a normal full sale. Every change carries a state condition (e.g. "promote only if
 * still POOL"); if the pool was changed elsewhere in the meantime, the change is skipped and
 * reported. Newly added or promoted instruments get a deep backfill scheduled through {@link
 * ScheduledSubmitter} (when called from the snapshot job the job slot is taken, so it retries
 * later).
 *
 * <p>Both entry points are {@code synchronized}, so the HTTP endpoint, the post-connect sync and
 * the snapshot job never plan and apply concurrently.
 */
public class HoldingSyncService implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(HoldingSyncService.class);
  static final String NOTE = "盈透持仓自动维护";

  public enum Action {
    /** Add as HOLDING. */
    ADD,
    /** Promote POOL to HOLDING. */
    PROMOTE,
    /** Position closed, back to POOL. */
    RETURN_TO_POOL,
    /** Position closed, removed from the pool. */
    REMOVE
  }

  /** One held US stock; a null instrumentId means it is not in the database. */
  public record Held(Long instrumentId, String symbol) {}

  public record Change(Action action, String symbol, Long instrumentId) {}

  /**
   * @param untouched held but left alone by the rules (e.g. benchmarks)
   * @param blocked why the plan is not executed; null means it can be executed
   */
  public record Plan(List<Change> changes, List<String> untouched, String blocked) {}

  public record Result(boolean applied, Plan plan, List<String> errors, String summary) {}

  private final AccountProperties props;
  private final AccountPositions source;
  private final InstrumentRepository instruments;
  private final PoolRepository pool;
  private final PoolService poolService;
  private final MarketDataFacade marketData;
  private final ScheduledSubmitter submitter;

  public HoldingSyncService(
      AccountProperties props,
      String configuredAccount,
      BrokerGateway broker,
      AccountGateway accounts,
      InstrumentRepository instruments,
      PoolRepository pool,
      PoolService poolService,
      MarketDataFacade marketData,
      JobRunRepository jobRuns) {
    this.props = props;
    this.source = new AccountPositions(configuredAccount, broker, accounts, instruments);
    this.instruments = instruments;
    this.pool = pool;
    this.poolService = poolService;
    this.marketData = marketData;
    this.submitter = new ScheduledSubmitter(jobRuns, "holding-sync-retry");
  }

  /**
   * Fetches IBKR positions now and syncs. apply=false only returns the plan and does not change the
   * pool (mapping still records instruments' IBKR conIds along the way).
   *
   * @param trigger trigger of the deep backfill job: MANUAL / SCHEDULE
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

  /**
   * Called from the snapshot job: uses the positions already fetched and mapped, and applies
   * directly.
   */
  public synchronized Result syncFromSnapshot(List<ValuedPosition> valued) {
    List<Held> held =
        valued.stream()
            .filter(v -> v.stock() && !v.cashEquivalent() && "USD".equals(v.position().currency()))
            .map(v -> new Held(v.instrumentId(), AccountPositions.normalize(v.symbol())))
            .toList();
    boolean brokerReturnedNothing =
        valued.stream().noneMatch(v -> v.position().quantity().signum() != 0);
    return run(held, brokerReturnedNothing, true, "SCHEDULE");
  }

  /**
   * @param brokerReturnedNothing IBKR returned no positions at all (before filtering out
   *     cash-management instruments and non-US stocks)
   */
  Result run(List<Held> held, boolean brokerReturnedNothing, boolean apply, String trigger) {
    List<PoolRepository.SyncRow> members = pool.findAllForSync();
    Map<Long, String> symbols = new HashMap<>();
    instruments
        .findByIds(members.stream().map(PoolRepository.SyncRow::instrumentId).toList())
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
        boolean ok =
            switch (c.action()) {
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
        submitter.submit(
            "持仓新增标的的深度回补", Jobs.DEEP_BACKFILL, trigger, () -> marketData.backfillPending(trigger));
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

  /** For when held is everything IBKR returned (tests). */
  static Plan plan(
      List<Held> held, List<PoolRepository.SyncRow> members, Map<Long, String> symbols) {
    return plan(held, held.isEmpty(), members, symbols);
  }

  /**
   * "Do not clear when positions are empty" only looks at IBKR's <b>raw</b> response: before 2.0.2
   * it looked at the list after removing cash-management instruments, so after selling everything
   * except SGOV the sync was blocked as incomplete data, HOLDING could never be cleared and every
   * daily snapshot was PARTIAL.
   */
  static Plan plan(
      List<Held> held,
      boolean brokerReturnedNothing,
      List<PoolRepository.SyncRow> members,
      Map<Long, String> symbols) {
    Map<Long, PoolRepository.SyncRow> byId = new HashMap<>();
    members.forEach(m -> byId.put(m.instrumentId(), m));
    long holdingNow = members.stream().filter(m -> m.role() == PoolRole.HOLDING).count();
    if (brokerReturnedNothing && holdingNow > 0) {
      return new Plan(
          List.of(), List.of(), "盈透返回的持仓为空，而池里还有 " + holdingNow + " 只 HOLDING，疑为数据不完整，不自动清空");
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
      changes.add(
          new Change(
              "POOL".equals(m.returnRole()) ? Action.RETURN_TO_POOL : Action.REMOVE,
              symbol,
              m.instrumentId()));
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
    String list =
        plan.changes().stream()
            .map(c -> label(c.action()) + " " + c.symbol())
            .collect(Collectors.joining("、"));
    return (applied ? "已执行 " : "计划 ")
        + list
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
    return props.cashEquivalents().stream()
        .map(AccountPositions::normalize)
        .collect(Collectors.toSet());
  }

  @Override
  public void close() {
    submitter.close();
  }
}
