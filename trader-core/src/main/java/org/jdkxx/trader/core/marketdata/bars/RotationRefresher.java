package org.jdkxx.trader.core.marketdata.bars;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;
import org.jdkxx.trader.common.ratelimit.Sleeper;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.core.marketdata.universe.UnknownSymbolGuard;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.BarSyncState;
import org.jdkxx.trader.storage.marketdata.BarSyncStateRepository;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Subscription rotation: per batch sub(KL_Day) → getKL(n) per instrument → upsert → stay subscribed
 * for holdSeconds → unsub. Uses zero history-bar quota (Futu's history quota is only 100
 * instruments per 7 days, so the full universe cannot use requestHistoryKL); one failing instrument
 * only records last_error and does not abort the batch.
 *
 * <p>Quota: the subscription quota (100 per account, counted per instrument × type) is shared with
 * the real-time quote subscriptions and with any other instance on the same account. The {@link
 * QuotaCoordinator} hook lets the real-time side pause (or shrink the batch) before a rotation and
 * resume after it. Running a rotation on the dev instance while production holds subscriptions
 * makes batch subscriptions get rejected (see CLAUDE.md).
 *
 * <p>Runs on the job thread and blocks on gateway futures with timeouts; never call it from a
 * gateway callback thread.
 */
public class RotationRefresher {

  private static final Logger log = LoggerFactory.getLogger(RotationRefresher.class);
  public static final String SOURCE = "FUTU_KL";

  /**
   * @param unsettled number of bars later than the write cutoff that were not written (today's bar
   *     when triggered intraday)
   */
  public record Result(int instruments, int ok, int failed, long bars, long unsettled) {}

  /**
   * Hooks around a rotation: pause / resume real-time subscriptions and report the batch size
   * available for this round (≤ 0 means use the configured value). {@link #afterRefresh()} always
   * runs, even when the rotation throws.
   */
  public interface QuotaCoordinator {
    default int beforeRefresh() {
      return 0;
    }

    default void afterRefresh() {}

    QuotaCoordinator NONE = new QuotaCoordinator() {};
  }

  private final MarketDataProperties.Refresh props;
  private final MarketDataGateway gateway;
  private final DailyBarRepository bars;
  private final BarSyncStateRepository states;
  private final SettledCutoff cutoff;
  private final UnknownSymbolGuard unknownSymbols;
  private final Sleeper sleeper;
  private volatile QuotaCoordinator coordinator = QuotaCoordinator.NONE;

  public RotationRefresher(
      MarketDataProperties.Refresh props,
      MarketDataGateway gateway,
      DailyBarRepository bars,
      BarSyncStateRepository states,
      SettledCutoff cutoff,
      UnknownSymbolGuard unknownSymbols,
      Sleeper sleeper) {
    this.props = props;
    this.gateway = gateway;
    this.bars = bars;
    this.states = states;
    this.cutoff = cutoff;
    this.unknownSymbols = unknownSymbols;
    this.sleeper = sleeper;
  }

  public void coordinator(QuotaCoordinator coordinator) {
    this.coordinator = coordinator == null ? QuotaCoordinator.NONE : coordinator;
  }

  public static <T> List<List<T>> batches(List<T> items, int size) {
    List<List<T>> out = new ArrayList<>();
    for (int i = 0; i < items.size(); i += size) {
      out.add(items.subList(i, Math.min(items.size(), i + size)));
    }
    return out;
  }

  /**
   * @param countFor number of bars to fetch per instrument (≤ 1000); 0 means skip. It is called
   *     more than once per instrument, so it must be a pure lookup
   */
  public Result refresh(
      List<InstrumentRow> targets,
      ToIntFunction<InstrumentRow> countFor,
      String label,
      JobContext ctx) {
    List<InstrumentRow> todo = targets.stream().filter(r -> countFor.applyAsInt(r) > 0).toList();
    if (todo.isEmpty()) {
      return new Result(0, 0, 0, 0, 0);
    }
    int limit = coordinator.beforeRefresh();
    int batchSize = Math.max(1, limit > 0 ? Math.min(props.batchSize(), limit) : props.batchSize());
    try {
      return run(todo, countFor, label, ctx, batchSize);
    } finally {
      coordinator.afterRefresh();
    }
  }

  private Result run(
      List<InstrumentRow> todo,
      ToIntFunction<InstrumentRow> countFor,
      String label,
      JobContext ctx,
      int batchSize) {
    int ok = 0;
    int failed = 0;
    long total = 0;
    long unsettled = 0;
    int done = 0;
    for (List<InstrumentRow> whole : batches(todo, batchSize)) {
      if (ctx.cancelled()) {
        ctx.partial("作业被取消");
        break;
      }
      List<InstrumentRow> batch = whole;
      List<Instrument> instruments = batch.stream().map(InstrumentRow::instrument).toList();
      long subscribedAt = System.nanoTime();
      try {
        gateway.subscribeDailyBars(instruments).get(30, TimeUnit.SECONDS);
      } catch (Exception e) {
        // Batch-level recovery: one renamed symbol must not poison the whole batch (on 2026-10-06
        // PSKY cost 90 instruments here in one go). After a successful re-subscribe the hold timer
        // restarts, because the broker's 1-minute minimum counts from the subscription that
        // actually took effect Note: a subscribe that timed out may still have taken effect at the
        // broker; that batch is not unsubscribed here and its quota is only released when the
        // connection closes
        Recovered r = recover(batch, instruments, e, ctx);
        if (r == null) {
          failed += batch.size();
          done += batch.size();
          continue;
        }
        failed += batch.size() - r.batch().size();
        done += batch.size() - r.batch().size();
        batch = r.batch();
        instruments = r.instruments();
        subscribedAt = System.nanoTime();
      }
      // Evaluate once per batch: a rotation may cross 16:15, and once it has, today's bar may be
      // written
      LocalDate cut = cutoff.current();
      for (InstrumentRow row : batch) {
        int n = countFor.applyAsInt(row);
        try {
          List<DailyBar> fetched =
              gateway.recentDailyBars(row.instrument(), n).get(30, TimeUnit.SECONDS);
          List<DailyBar> list = SettledCutoff.settled(fetched, cut);
          unsettled += fetched.size() - list.size();
          int written = bars.upsertAll(row.id(), list, SOURCE);
          total += written;
          LocalDate earliest = list.isEmpty() ? null : list.get(0).tradeDate();
          LocalDate latest = list.isEmpty() ? null : list.get(list.size() - 1).tradeDate();
          states.success(
              row.id(),
              n >= props.fullCount() ? BarSyncState.DEPTH_KL1000 : BarSyncState.DEPTH_NONE,
              earliest,
              latest,
              written,
              false);
          ok++;
        } catch (Exception e) {
          failed++;
          states.error(row.id(), e.getMessage() == null ? e.toString() : e.getMessage());
          log.warn("{} 取 {} 根日 K 失败：{}", row.symbol(), n, e.toString());
        }
        done++;
        ctx.progress(
            label
                + " "
                + done
                + "/"
                + todo.size()
                + "（成功 "
                + ok
                + "，失败 "
                + failed
                + "，K 线 "
                + total
                + "）");
      }
      // Futu refuses to unsubscribe anything subscribed for less than 1 minute, so sleep out the
      // remainder of holdSeconds (measured with the monotonic clock from the subscription) before
      // unsubscribing.
      // If the sleep is interrupted the loop breaks before unsubscribing; the batch then holds its
      // quota until the connection closes
      long elapsed = Duration.ofNanos(System.nanoTime() - subscribedAt).toMillis();
      long hold = props.holdSeconds() * 1000L - elapsed;
      if (hold > 0) {
        try {
          sleeper.sleep(Duration.ofMillis(hold));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          ctx.partial("等待反订阅时被中断");
          break;
        }
      }
      try {
        gateway.unsubscribeDailyBars(instruments).get(30, TimeUnit.SECONDS);
      } catch (Exception e) {
        log.warn("反订阅 {} 只失败（额度会在连接关闭时释放）：{}", instruments.size(), e.toString());
      }
    }
    if (failed > 0) {
      ctx.partial(failed + " 只失败");
    }
    if (unsettled > 0) {
      log.info("{}：丢弃 {} 根晚于写库截止日的未收盘 K 线", label, unsettled);
    }
    return new Result(todo.size(), ok, failed, total, unsettled);
  }

  private record Recovered(List<InstrumentRow> batch, List<Instrument> instruments) {}

  /**
   * After a rejected subscription, try batch-level recovery once: identify the symbols Futu does
   * not know → drop them → re-subscribe once. Returning {@code null} means it could not be saved
   * (the log line and PARTIAL are already recorded here) and the caller counts the whole batch as
   * failed, as before. The dropped ones count as failures and the rest are collected as usual: that
   * is "one bad symbol only costs itself".
   */
  private Recovered recover(
      List<InstrumentRow> batch, List<Instrument> instruments, Exception failure, JobContext ctx) {
    UnknownSymbolGuard.Outcome out =
        unknownSymbols.inspect(instruments, failure, true).orElse(null);
    if (out != null && out.canRetry()) {
      List<InstrumentRow> kept =
          batch.stream()
              .filter(r -> !out.exclude().contains(r.symbol().toUpperCase(Locale.ROOT)))
              .toList();
      if (!kept.isEmpty()) {
        List<Instrument> keptInstruments = kept.stream().map(InstrumentRow::instrument).toList();
        try {
          gateway.subscribeDailyBars(keptInstruments).get(30, TimeUnit.SECONDS);
          ctx.partial(out.detail());
          log.warn("剔除 {} 只未知代码后重订成功，本批继续 {} 只", batch.size() - kept.size(), kept.size());
          return new Recovered(kept, keptInstruments);
        } catch (Exception retry) {
          log.warn("剔除 {} 只未知代码后重订仍失败：{}", batch.size() - kept.size(), retry.toString());
        }
      }
    }
    log.warn("订阅 {} 只失败：{}", instruments.size(), failure.toString());
    ctx.partial("订阅失败：" + (out == null ? failure.getMessage() : out.detail()));
    return null;
  }
}
