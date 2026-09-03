package org.jdkxx.trader.core.marketdata.bars;

import org.jdkxx.trader.common.ratelimit.Sleeper;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.BarSyncState;
import org.jdkxx.trader.storage.marketdata.BarSyncStateRepository;
import org.jdkxx.trader.storage.marketdata.DailyBarRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.ToIntFunction;

/**
 * 订阅轮转：分批 sub(KL_Day) → 逐只 getKL(n) → upsert → 停留满 holdSeconds → unsub。
 * 零历史额度；一只失败只记 last_error 不中断整批。
 */
public class RotationRefresher {

    private static final Logger log = LoggerFactory.getLogger(RotationRefresher.class);
    public static final String SOURCE = "FUTU_KL";

    public record Result(int instruments, int ok, int failed, long bars) {
    }

    /** 轮转前后的钩子：暂停/恢复实时订阅，并给出本轮可用的批次上限（≤0 表示用配置值）。 */
    public interface QuotaCoordinator {
        default int beforeRefresh() {
            return 0;
        }

        default void afterRefresh() {
        }

        QuotaCoordinator NONE = new QuotaCoordinator() {
        };
    }

    private final MarketDataProperties.Refresh props;
    private final MarketDataGateway gateway;
    private final DailyBarRepository bars;
    private final BarSyncStateRepository states;
    private final Sleeper sleeper;
    private volatile QuotaCoordinator coordinator = QuotaCoordinator.NONE;

    public RotationRefresher(MarketDataProperties.Refresh props, MarketDataGateway gateway, DailyBarRepository bars,
                             BarSyncStateRepository states, Sleeper sleeper) {
        this.props = props;
        this.gateway = gateway;
        this.bars = bars;
        this.states = states;
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
     * @param countFor 每只要取的根数（≤1000）；返回 0 表示跳过
     */
    public Result refresh(List<InstrumentRow> targets, ToIntFunction<InstrumentRow> countFor, String label, JobContext ctx) {
        List<InstrumentRow> todo = targets.stream().filter(r -> countFor.applyAsInt(r) > 0).toList();
        if (todo.isEmpty()) {
            return new Result(0, 0, 0, 0);
        }
        int limit = coordinator.beforeRefresh();
        int batchSize = Math.max(1, limit > 0 ? Math.min(props.batchSize(), limit) : props.batchSize());
        try {
            return run(todo, countFor, label, ctx, batchSize);
        } finally {
            coordinator.afterRefresh();
        }
    }

    private Result run(List<InstrumentRow> todo, ToIntFunction<InstrumentRow> countFor, String label, JobContext ctx, int batchSize) {
        int ok = 0;
        int failed = 0;
        long total = 0;
        int done = 0;
        for (List<InstrumentRow> batch : batches(todo, batchSize)) {
            if (ctx.cancelled()) {
                ctx.partial("作业被取消");
                break;
            }
            List<Instrument> instruments = batch.stream().map(InstrumentRow::instrument).toList();
            long subscribedAt = System.nanoTime();
            try {
                gateway.subscribeDailyBars(instruments).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("订阅 {} 只失败：{}", instruments.size(), e.toString());
                ctx.partial("订阅失败：" + e.getMessage());
                failed += batch.size();
                done += batch.size();
                continue;
            }
            for (InstrumentRow row : batch) {
                int n = countFor.applyAsInt(row);
                try {
                    List<DailyBar> list = gateway.recentDailyBars(row.instrument(), n).get(30, TimeUnit.SECONDS);
                    int written = bars.upsertAll(row.id(), list, SOURCE);
                    total += written;
                    LocalDate earliest = list.isEmpty() ? null : list.get(0).tradeDate();
                    LocalDate latest = list.isEmpty() ? null : list.get(list.size() - 1).tradeDate();
                    states.success(row.id(), n >= props.fullCount() ? BarSyncState.DEPTH_KL1000 : BarSyncState.DEPTH_NONE,
                            earliest, latest, written, false);
                    ok++;
                } catch (Exception e) {
                    failed++;
                    states.error(row.id(), e.getMessage() == null ? e.toString() : e.getMessage());
                    log.warn("{} 取 {} 根日 K 失败：{}", row.symbol(), n, e.toString());
                }
                done++;
                ctx.progress(label + " " + done + "/" + todo.size() + "（成功 " + ok + "，失败 " + failed + "，K 线 " + total + "）");
            }
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
        return new Result(todo.size(), ok, failed, total);
    }
}
