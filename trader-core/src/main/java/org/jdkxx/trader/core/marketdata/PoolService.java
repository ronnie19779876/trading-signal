package org.jdkxx.trader.core.marketdata;

import org.jdkxx.trader.core.marketdata.bars.DeepBackfillService;
import org.jdkxx.trader.core.marketdata.jobs.JobService;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.PoolRepository;
import org.jdkxx.trader.storage.marketdata.PoolRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/** 标的池维护。加入池后自动排一个深度回补作业（没有作业在跑时）。 */
public class PoolService {

    private static final Logger log = LoggerFactory.getLogger(PoolService.class);

    public record AddResult(PoolRow member, Long backfillJobId, String note) {
    }

    private final MarketDataProperties props;
    private final PoolRepository pool;
    private final InstrumentDirectory directory;
    private final JobService jobs;
    private final DeepBackfillService deep;

    public PoolService(MarketDataProperties props, PoolRepository pool, InstrumentDirectory directory, JobService jobs, DeepBackfillService deep) {
        this.props = props;
        this.pool = pool;
        this.directory = directory;
        this.jobs = jobs;
        this.deep = deep;
    }

    public List<PoolRow> members() {
        return pool.findAll();
    }

    public AddResult add(String symbol, PoolRole role, String note) {
        InstrumentRow row = directory.require(symbol);
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
        return pool.delete(row.id());
    }
}
