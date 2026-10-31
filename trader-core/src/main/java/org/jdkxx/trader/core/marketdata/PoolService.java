package org.jdkxx.trader.core.marketdata;

import java.util.List;
import java.util.Optional;
import org.jdkxx.trader.core.marketdata.bars.DeepBackfillService;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.PoolRepository;
import org.jdkxx.trader.storage.marketdata.PoolRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pool maintenance. Adding to the pool automatically queues a deep backfill job (when no other job
 * is running). Symbols not yet in the database (ETFs, ADRs that are not constituents, ...) are
 * first resolved against Futu's static info and registered automatically if Futu knows them.
 *
 * <p>Every change runs the {@link #afterChange} hook (real-time subscription reconciliation). Note
 * that changing a role or adding a member also triggers a 20-year deep backfill, which can bring
 * back broker dirty bars that were cleaned up before; check {@code phantomBars} in the daily check
 * afterwards.
 */
public class PoolService {

  private static final Logger log = LoggerFactory.getLogger(PoolService.class);

  public record AddResult(PoolRow member, Long backfillJobId, String note) {}

  private final MarketDataProperties props;
  private final PoolRepository pool;
  private final InstrumentDirectory directory;
  private final JobService jobs;
  private final DeepBackfillService deep;
  private final InstrumentRepository instruments;
  private final MarketDataGateway gateway;
  private volatile Runnable afterChange = () -> {};

  /**
   * Hook run after the pool changes (real-time subscription reconciliation). It must be {@code
   * QuoteSubscriptionService.onPoolChanged}, not {@code reconcile} directly: only the former
   * respects auto-subscribe=false on the dev instance.
   */
  public void afterChange(Runnable hook) {
    this.afterChange = hook == null ? () -> {} : hook;
  }

  public PoolService(
      MarketDataProperties props,
      PoolRepository pool,
      InstrumentDirectory directory,
      JobService jobs,
      DeepBackfillService deep,
      InstrumentRepository instruments,
      MarketDataGateway gateway) {
    this.props = props;
    this.pool = pool;
    this.directory = directory;
    this.jobs = jobs;
    this.deep = deep;
    this.instruments = instruments;
    this.gateway = gateway;
  }

  public List<PoolRow> members() {
    return pool.findAll();
  }

  public AddResult add(String symbol, PoolRole role, String note) {
    InstrumentRow row =
        instruments.find(Instrument.us(symbol)).orElseGet(() -> register(Instrument.us(symbol)));
    if (!"RESOLVED".equals(row.resolveStatus())) {
      throw new IllegalStateException(
          row.symbol() + " 富途尚未解析（resolve_status=" + row.resolveStatus() + "），先同步成分股");
    }
    // The size cap applies only to the POOL role and only when this call would add a new POOL
    // member (re-adding an existing POOL member or adding a HOLDING / BENCHMARK never counts
    // against it)
    Optional<PoolRow> existing = pool.find(row.id());
    if (role == PoolRole.POOL
        && existing.map(p -> p.role() != PoolRole.POOL).orElse(true)
        && pool.count(PoolRole.POOL) >= props.pool().maxSize()) {
      throw new IllegalStateException("标的池已满（上限 " + props.pool().maxSize() + "）");
    }
    pool.upsert(row.id(), role, note);
    PoolRow member = pool.find(row.id()).orElseThrow();
    runHook();
    Long jobId = null;
    String hint;
    try {
      jobId = jobs.submit(Jobs.DEEP_BACKFILL, "MANUAL", ctx -> deep.backfill(row, ctx) + " 根");
      hint = "已排深度回补作业";
    } catch (IllegalStateException e) {
      hint = "深度回补未自动开始：" + e.getMessage() + "；稍后 POST /api/bars/backfill/" + row.symbol();
      log.info("{} 加入池，{}", row.symbol(), hint);
    }
    return new AddResult(member, jobId, hint);
  }

  public boolean remove(String symbol) {
    InstrumentRow row = directory.require(symbol);
    boolean removed = pool.delete(row.id());
    if (removed) {
      runHook();
    }
    return removed;
  }

  /**
   * Called after the pool was changed directly elsewhere (holdings sync); triggers real-time
   * subscription reconciliation.
   */
  public void notifyChanged() {
    runHook();
  }

  /**
   * A symbol not in the database: resolve it against Futu and register it if Futu knows it
   * (brokerId ≠ 0). Futu answers even for unknown symbols (name "unknown stock", brokerId=0), so a
   * returned row alone proves nothing.
   */
  private InstrumentRow register(Instrument instrument) {
    InstrumentStatic s;
    try {
      s =
          gateway
              .staticInfo(List.of(instrument))
              .get(15, java.util.concurrent.TimeUnit.SECONDS)
              .stream()
              .filter(x -> x.instrument().equals(instrument))
              .findFirst()
              .orElse(null);
    } catch (Exception e) {
      throw new IllegalStateException("向富途解析 " + instrument.symbol() + " 失败：" + e.getMessage(), e);
    }
    if (s == null || s.brokerId() == 0) {
      throw new java.util.NoSuchElementException("标的 " + instrument.symbol() + " 不在库里，富途也不认识这个代码");
    }
    long id = instruments.upsert(instrument, s.name());
    instruments.updateStatic(id, s);
    log.info("{} 不在成分股里，已按富途静态信息建档（{}）", instrument.symbol(), s.name());
    return instruments.findById(id).orElseThrow();
  }

  /**
   * The hook is best effort: a failing reconciliation must not fail the pool change that already
   * committed.
   */
  private void runHook() {
    try {
      afterChange.run();
    } catch (RuntimeException e) {
      log.warn("池变动钩子失败：{}", e.toString());
    }
  }
}
