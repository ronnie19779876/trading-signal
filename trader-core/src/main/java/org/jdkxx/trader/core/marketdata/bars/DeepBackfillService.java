package org.jdkxx.trader.core.marketdata.bars;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.HistoryQuota;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.BarSyncState;
import org.jdkxx.trader.storage.marketdata.BarSyncStateRepository;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.RehabFactorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deep backfill (20 years): pool and holdings only; each instrument costs 1 history-bar quota unit
 * (Futu: 100 per rolling 7 days), and the quota guard keeps quota-reserve units unused.
 *
 * <p>Like the rotation, it truncates at the {@link SettledCutoff} so a backfill triggered intraday
 * never stores today's unfinished bar. A deep backfill re-fetches the broker's whole history,
 * including dirty holiday bars that were cleaned up before; check {@code phantomBars} in the daily
 * check after one runs.
 */
public class DeepBackfillService {

  private static final Logger log = LoggerFactory.getLogger(DeepBackfillService.class);
  public static final String SOURCE = "FUTU_HIST";

  private final MarketDataProperties props;
  private final MarketDataGateway gateway;
  private final DailyBarRepository bars;
  private final RehabFactorRepository rehabs;
  private final BarSyncStateRepository states;
  private final UniverseScope scope;
  private final SettledCutoff cutoff;

  public DeepBackfillService(
      MarketDataProperties props,
      MarketDataGateway gateway,
      DailyBarRepository bars,
      RehabFactorRepository rehabs,
      BarSyncStateRepository states,
      UniverseScope scope,
      SettledCutoff cutoff) {
    this.props = props;
    this.gateway = gateway;
    this.bars = bars;
    this.rehabs = rehabs;
    this.states = states;
    this.scope = scope;
    this.cutoff = cutoff;
  }

  /** Remaining quota minus the reserve: how many instruments this round may still spend. */
  public int availableQuota() throws Exception {
    HistoryQuota q = gateway.historyQuota().get(15, TimeUnit.SECONDS);
    return Math.max(0, q.remain() - props.history().quotaReserve());
  }

  /**
   * Backfills one instrument (part of a job body); throws QuotaExhaustedException when quota is
   * short. An instrument already paid for within the window is fetched anyway, because Futu does
   * not charge a repeat request for the same instrument again.
   */
  public int backfill(InstrumentRow row, JobContext ctx) throws Exception {
    if (availableQuota() <= 0 && !alreadyCounted(row)) {
      states.error(row.id(), "待历史额度（7 天滚动窗口）");
      throw new QuotaExhaustedException(row.symbol());
    }
    // Truncate at the last settled trading day: an intraday trigger (holdings sync after an
    // intraday IBKR reconnect, a manual pool add) must not store today's unfinished bar. The
    // request already ends at the cutoff; filtering again guards against the broker returning more
    // than was asked for
    LocalDate cut = cutoff.current();
    ctx.progress("深度回补 " + row.symbol() + "：拉取 " + props.history().from() + " ～ " + cut);
    List<DailyBar> fetched =
        gateway
            .historyDailyBars(row.instrument(), props.history().from(), cut)
            .get(180, TimeUnit.SECONDS);
    List<DailyBar> list = SettledCutoff.settled(fetched, cut);
    if (list.size() < fetched.size()) {
      log.info("{} 深度回补丢弃 {} 根晚于 {} 的未收盘 K 线", row.symbol(), fetched.size() - list.size(), cut);
    }
    int written = bars.upsertAll(row.id(), list, SOURCE);
    refreshRehab(row);
    LocalDate earliest = list.isEmpty() ? null : list.get(0).tradeDate();
    LocalDate latest = list.isEmpty() ? null : list.get(list.size() - 1).tradeDate();
    states.success(row.id(), BarSyncState.DEPTH_HIST, earliest, latest, written, true);
    log.info("{} 深度回补 {} 根（{} ～ {}）", row.symbol(), written, earliest, latest);
    return written;
  }

  public void refreshRehab(InstrumentRow row) throws Exception {
    List<RehabFactor> factors = gateway.rehab(row.instrument()).get(30, TimeUnit.SECONDS);
    rehabs.replaceAll(row.id(), factors);
    states.rehabFetched(row.id());
  }

  /**
   * From the universe instruments that are due (already sorted least recently refreshed first),
   * pick which to refresh this time: skip those already targeted (pool / holdings), at most
   * ceil(universe / spreadDays); spreadDays ≤ 1 means no cap.
   */
  static List<Long> dueToday(
      List<Long> staleOldestFirst,
      java.util.Set<Long> alreadyTargeted,
      int universeSize,
      int spreadDays) {
    long cap =
        spreadDays <= 1
            ? Long.MAX_VALUE
            : Math.max(1, (universeSize + spreadDays - 1) / spreadDays);
    return staleOldestFirst.stream()
        .filter(id -> !alreadyTargeted.contains(id))
        .limit(cap)
        .toList();
  }

  /**
   * Price-adjustment factor refresh: all=true covers the full universe; otherwise every pool and
   * holdings instrument is refreshed, plus universe instruments not refreshed for 7+ days, least
   * recently refreshed first, at most 1/rehab-spread-days of the universe per run. Rate limit
   * 60/30s; uses no history quota.
   *
   * <p>Why the cap: if the whole universe was refreshed on the same day, it all falls due again in
   * the same increment 7 days later (521 instruments, roughly 300 extra seconds). On 2026-09-10
   * that made the increment run 691 seconds and push the valuation job out of its slot. With the
   * cap, a cluster of due dates spreads itself out within a few days and stays spread.
   *
   * <p>A rate-limited request is retried once after 10 seconds (on the job thread); any other
   * failure only counts as failed and the loop continues.
   */
  public String refreshRehab(boolean all, JobContext ctx) {
    java.util.Map<Long, InstrumentRow> targets = new java.util.LinkedHashMap<>();
    scope.poolAndHoldings().forEach(r -> targets.put(r.id(), r));
    List<InstrumentRow> universe = scope.universe();
    String scopeNote;
    if (all) {
      universe.forEach(r -> targets.put(r.id(), r));
      scopeNote = "（全量）";
    } else {
      java.util.Map<Long, InstrumentRow> byId = new java.util.HashMap<>();
      universe.forEach(r -> byId.put(r.id(), r));
      List<Long> stale =
          states.rehabStale(
              byId.keySet(), java.time.Instant.now().minus(java.time.Duration.ofDays(7)));
      long staleOutside = stale.stream().filter(id -> !targets.containsKey(id)).count();
      List<Long> due =
          dueToday(stale, targets.keySet(), universe.size(), props.refresh().rehabSpreadDays());
      due.forEach(id -> targets.put(id, byId.get(id)));
      scopeNote =
          "（池/持仓 + 到期 "
              + staleOutside
              + " 只中最久未刷的 "
              + due.size()
              + " 只"
              + (staleOutside > due.size() ? "，其余 " + (staleOutside - due.size()) + " 只顺延" : "")
              + "）";
    }
    int ok = 0;
    int failed = 0;
    int done = 0;
    for (InstrumentRow row : targets.values()) {
      if (ctx.cancelled()) {
        ctx.partial("作业被取消");
        break;
      }
      try {
        try {
          refreshRehab(row);
        } catch (Exception first) {
          if (!isRateLimited(first)) {
            throw first;
          }
          log.info("{} 复权因子被限频，10 秒后重试一次", row.symbol());
          Thread.sleep(10_000);
          refreshRehab(row);
        }
        ok++;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        ctx.partial("作业被中断");
        break;
      } catch (Exception e) {
        failed++;
        log.warn("{} 复权因子刷新失败：{}", row.symbol(), e.toString());
      }
      done++;
      if (done % 20 == 0 || done == targets.size()) {
        ctx.progress("复权因子 " + done + "/" + targets.size() + "（失败 " + failed + "）");
      }
    }
    if (failed > 0) {
      ctx.partial(failed + " 只复权因子失败");
    }
    return "复权因子刷新 " + ok + "/" + targets.size() + " 只" + scopeNote;
  }

  /**
   * Quota was already spent on it inside the window (a repeat request is not counted again), so it
   * can be fetched directly. Uses 6 days rather than 7 as a safety margin against the edge of the
   * rolling window.
   */
  private boolean alreadyCounted(InstrumentRow row) {
    Optional<BarSyncState> s = states.find(row.id());
    return s.map(BarSyncState::histQuotaUsedAt)
        .map(t -> t.isAfter(java.time.Instant.now().minus(java.time.Duration.ofDays(6))))
        .orElse(false);
  }

  /**
   * Backfills, one after another within the quota, the pool and holdings instruments that are not
   * yet at HIST20Y depth (job body). Stops at the first quota exhaustion; the rest wait for quota
   * to free up.
   */
  public String backfillPending(JobContext ctx) throws Exception {
    List<InstrumentRow> targets =
        scope.poolAndHoldings().stream()
            .filter(
                r ->
                    states
                        .find(r.id())
                        .map(s -> !BarSyncState.DEPTH_HIST.equals(s.depth()))
                        .orElse(true))
            .toList();
    if (targets.isEmpty()) {
      return "池与持仓标的均已有 20 年深度";
    }
    int done = 0;
    long total = 0;
    for (InstrumentRow row : targets) {
      if (ctx.cancelled()) {
        ctx.partial("作业被取消");
        break;
      }
      try {
        total += backfill(row, ctx);
        done++;
      } catch (QuotaExhaustedException e) {
        ctx.partial("历史额度用尽，剩余 " + (targets.size() - done) + " 只待下周");
        break;
      } catch (Exception e) {
        states.error(row.id(), e.getMessage() == null ? e.toString() : e.getMessage());
        ctx.partial(row.symbol() + " 失败：" + e.getMessage());
      }
    }
    return "深度回补 " + done + "/" + targets.size() + " 只，K 线 " + total + " 根";
  }

  /**
   * Whether any cause in the chain looks like a Futu rate-limit rejection. This matches Futu's
   * Chinese error text on purpose: it only decides whether to wait and retry once, never whether
   * data is valid.
   */
  private static boolean isRateLimited(Throwable t) {
    Throwable c = t;
    while (c != null) {
      String m = c.getMessage();
      if (m != null && (m.contains("频率太高") || m.contains("频率") || m.contains("限频"))) {
        return true;
      }
      c = c.getCause();
    }
    return false;
  }

  public static class QuotaExhaustedException extends Exception {
    public QuotaExhaustedException(String symbol) {
      super("历史 K 线额度不足，" + symbol + " 排队到额度释放后");
    }
  }
}
