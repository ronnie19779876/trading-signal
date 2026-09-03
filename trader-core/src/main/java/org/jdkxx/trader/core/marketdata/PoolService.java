package org.jdkxx.trader.core.marketdata;

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

import java.util.List;
import java.util.Optional;

/**
 * 标的池维护。加入池后自动排一个深度回补作业（没有作业在跑时）。
 * 库里没有的代码（如 ETF、非成分股的 ADR）先向富途解析静态信息，认识就自动建档。
 */
public class PoolService {

    private static final Logger log = LoggerFactory.getLogger(PoolService.class);

    public record AddResult(PoolRow member, Long backfillJobId, String note) {
    }

    private final MarketDataProperties props;
    private final PoolRepository pool;
    private final InstrumentDirectory directory;
    private final JobService jobs;
    private final DeepBackfillService deep;
    private final InstrumentRepository instruments;
    private final MarketDataGateway gateway;
    private volatile Runnable afterChange = () -> { };

    /** 池变动后的钩子（实时订阅对账）。 */
    public void afterChange(Runnable hook) {
        this.afterChange = hook == null ? () -> { } : hook;
    }

    public PoolService(MarketDataProperties props, PoolRepository pool, InstrumentDirectory directory, JobService jobs,
                       DeepBackfillService deep, InstrumentRepository instruments, MarketDataGateway gateway) {
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
        InstrumentRow row = instruments.find(Instrument.us(symbol)).orElseGet(() -> register(Instrument.us(symbol)));
        if (!"RESOLVED".equals(row.resolveStatus())) {
            throw new IllegalStateException(row.symbol() + " 富途尚未解析（resolve_status=" + row.resolveStatus() + "），先同步成分股");
        }
        Optional<PoolRow> existing = pool.find(row.id());
        if (role == PoolRole.POOL && existing.map(p -> p.role() != PoolRole.POOL).orElse(true)
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

    /** 库里没有的代码：向富途解析，认识（brokerId≠0）就建档。 */
    private InstrumentRow register(Instrument instrument) {
        InstrumentStatic s;
        try {
            s = gateway.staticInfo(List.of(instrument)).get(15, java.util.concurrent.TimeUnit.SECONDS).stream()
                    .filter(x -> x.instrument().equals(instrument)).findFirst().orElse(null);
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

    private void runHook() {
        try {
            afterChange.run();
        } catch (RuntimeException e) {
            log.warn("池变动钩子失败：{}", e.toString());
        }
    }
}
