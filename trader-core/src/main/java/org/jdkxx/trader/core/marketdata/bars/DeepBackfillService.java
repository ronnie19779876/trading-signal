package org.jdkxx.trader.core.marketdata.bars;

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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 深度回补（20 年）：只对池与持仓；每只占 1 个历史额度，额度守卫留 quota-reserve 个不用。
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
    private final ZoneId zone;

    public DeepBackfillService(MarketDataProperties props, MarketDataGateway gateway, DailyBarRepository bars,
                               RehabFactorRepository rehabs, BarSyncStateRepository states, UniverseScope scope) {
        this.props = props;
        this.gateway = gateway;
        this.bars = bars;
        this.rehabs = rehabs;
        this.states = states;
        this.scope = scope;
        this.zone = ZoneId.of(props.zone());
    }

    /** 额度剩余减去预留后可用于本轮的数量。 */
    public int availableQuota() throws Exception {
        HistoryQuota q = gateway.historyQuota().get(15, TimeUnit.SECONDS);
        return Math.max(0, q.remain() - props.history().quotaReserve());
    }

    /** 回补一只（作业体的一部分）；额度不足抛 QuotaExhaustedException。 */
    public int backfill(InstrumentRow row, JobContext ctx) throws Exception {
        if (availableQuota() <= 0 && !alreadyCounted(row)) {
            states.error(row.id(), "待历史额度（7 天滚动窗口）");
            throw new QuotaExhaustedException(row.symbol());
        }
        LocalDate today = LocalDate.now(zone);
        ctx.progress("深度回补 " + row.symbol() + "：拉取 " + props.history().from() + " ～ " + today);
        List<DailyBar> list = gateway.historyDailyBars(row.instrument(), props.history().from(), today).get(180, TimeUnit.SECONDS);
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
    }

    /** 7 天内已经为它用过额度（重复请求不再计数），可以直接拉。 */
    private boolean alreadyCounted(InstrumentRow row) {
        Optional<BarSyncState> s = states.find(row.id());
        return s.map(BarSyncState::histQuotaUsedAt).map(t -> t.isAfter(java.time.Instant.now().minus(java.time.Duration.ofDays(6)))).orElse(false);
    }

    /** 池与持仓里还没到 HIST20Y 深度的，按额度依次回补（作业体）。 */
    public String backfillPending(JobContext ctx) throws Exception {
        List<InstrumentRow> targets = scope.poolAndHoldings().stream()
                .filter(r -> states.find(r.id()).map(s -> !BarSyncState.DEPTH_HIST.equals(s.depth())).orElse(true))
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

    public static class QuotaExhaustedException extends Exception {
        public QuotaExhaustedException(String symbol) {
            super("历史 K 线额度不足，" + symbol + " 排队到额度释放后");
        }
    }
}
