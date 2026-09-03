package org.jdkxx.trader.core.marketdata.universe;

import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.domain.IndexCode;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.ConstituentRow;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * 成分股同步：来源 → instrument 幂等入库 → 与现任成分做集合差（新增 since / 退出 until）→ 富途静态信息解析。
 * 任一指数的来源失败时保留该指数旧成分并记录，不中断另一指数。
 */
public class UniverseSyncService {

    private static final Logger log = LoggerFactory.getLogger(UniverseSyncService.class);

    public record IndexResult(IndexCode index, int fetched, int added, int removed, int unchanged, String error) {
    }

    private final MarketDataProperties props;
    private final UniverseSource source;
    private final SpyHoldingsCrossCheck spyCheck;
    private final InstrumentRepository instruments;
    private final IndexConstituentRepository constituents;
    private final MarketDataGateway gateway;
    private final ZoneId zone;

    public UniverseSyncService(MarketDataProperties props, UniverseSource source, SpyHoldingsCrossCheck spyCheck,
                               InstrumentRepository instruments, IndexConstituentRepository constituents,
                               MarketDataGateway gateway) {
        this.props = props;
        this.source = source;
        this.spyCheck = spyCheck;
        this.instruments = instruments;
        this.constituents = constituents;
        this.gateway = gateway;
        this.zone = ZoneId.of(props.zone());
    }

    /** 完整同步（作业体）。 */
    public String sync(JobContext ctx) {
        List<String> parts = new ArrayList<>();
        Map<IndexCode, List<ConstituentEntry>> fetched = new LinkedHashMap<>();
        for (IndexCode index : IndexCode.values()) {
            ctx.progress("下载 " + index.displayName() + " 成分股");
            try {
                fetched.put(index, source.fetch(index));
            } catch (RuntimeException e) {
                log.warn("{} 成分股下载失败：{}", index, e.toString());
                ctx.partial(index.displayName() + " 来源失败：" + e.getMessage());
            }
        }
        for (Map.Entry<IndexCode, List<ConstituentEntry>> e : fetched.entrySet()) {
            IndexResult r = apply(e.getKey(), e.getValue(), source.name());
            parts.add(e.getKey() + " 抓到 " + r.fetched() + "：新增 " + r.added() + "、退出 " + r.removed() + "、不变 " + r.unchanged());
        }
        if (props.universe().crossCheckSpy() && fetched.containsKey(IndexCode.SP500)) {
            ctx.progress("用 SPY 持仓交叉核对标普 500");
            parts.add(crossCheck(fetched.get(IndexCode.SP500)));
        }
        ctx.progress("向富途解析静态信息");
        parts.add(resolveStatics(ctx));
        return String.join("；", parts);
    }

    /** CSV 导入（同一套集合差逻辑）。 */
    public List<IndexResult> importCsv(String csv) {
        List<ConstituentEntry> entries = CsvUniverse.parse(csv);
        Map<IndexCode, List<ConstituentEntry>> byIndex = new LinkedHashMap<>();
        for (ConstituentEntry e : entries) {
            byIndex.computeIfAbsent(e.index(), k -> new ArrayList<>()).add(e);
        }
        List<IndexResult> out = new ArrayList<>();
        for (Map.Entry<IndexCode, List<ConstituentEntry>> e : byIndex.entrySet()) {
            out.add(apply(e.getKey(), e.getValue(), "CSV"));
        }
        return out;
    }

    IndexResult apply(IndexCode index, List<ConstituentEntry> entries, String sourceName) {
        LocalDate today = LocalDate.now(zone);
        Map<Long, ConstituentEntry> wanted = new LinkedHashMap<>();
        for (ConstituentEntry e : entries) {
            long id = instruments.upsert(Instrument.us(e.symbol()), e.name());
            wanted.putIfAbsent(id, e);
        }
        Map<Long, ConstituentRow> current = new HashMap<>();
        for (ConstituentRow r : constituents.current(index)) {
            current.put(r.instrumentId(), r);
        }
        int added = 0;
        int unchanged = 0;
        for (Map.Entry<Long, ConstituentEntry> w : wanted.entrySet()) {
            ConstituentEntry e = w.getValue();
            if (current.containsKey(w.getKey())) {
                constituents.updateSector(index, w.getKey(), e.sector(), e.subIndustry());
                unchanged++;
            } else {
                constituents.add(index, w.getKey(), e.sector(), e.subIndustry(), today, sourceName);
                added++;
            }
        }
        int removed = 0;
        for (Long id : current.keySet()) {
            if (!wanted.containsKey(id)) {
                constituents.close(index, id, today);
                removed++;
            }
        }
        log.info("{} 成分股同步（{}）：抓到 {}，新增 {}，退出 {}，不变 {}", index, sourceName, wanted.size(), added, removed, unchanged);
        return new IndexResult(index, wanted.size(), added, removed, unchanged, null);
    }

    private String crossCheck(List<ConstituentEntry> sp500) {
        try {
            Set<String> spy = spyCheck.tickers();
            Set<String> wiki = new LinkedHashSet<>();
            sp500.forEach(e -> wiki.add(e.symbol()));
            Set<String> onlyWiki = new TreeSet<>(wiki);
            onlyWiki.removeAll(spy);
            Set<String> onlySpy = new TreeSet<>(spy);
            onlySpy.removeAll(wiki);
            return "SPY 交叉核对：持仓 " + spy.size() + " 只，仅在 Wikipedia " + onlyWiki.size() + " " + head(onlyWiki)
                    + "，仅在 SPY " + onlySpy.size() + " " + head(onlySpy);
        } catch (RuntimeException e) {
            log.warn("SPY 交叉核对失败：{}", e.toString());
            return "SPY 交叉核对失败：" + e.getMessage();
        }
    }

    private static String head(Set<String> s) {
        List<String> l = new ArrayList<>(s);
        return l.size() <= 12 ? l.toString() : l.subList(0, 12) + "…";
    }

    /** 对 PENDING / UNRESOLVED 的标的分批向富途取静态信息；富途不认识的标成 UNRESOLVED。 */
    public String resolveStatics(JobContext ctx) {
        List<InstrumentRow> pending = instruments.findAll().stream()
                .filter(r -> !"RESOLVED".equals(r.resolveStatus())).toList();
        if (pending.isEmpty()) {
            return "静态信息无需解析";
        }
        int resolved = 0;
        int unresolved = 0;
        int batch = Math.max(1, props.universe().staticBatchSize());
        for (int i = 0; i < pending.size(); i += batch) {
            List<InstrumentRow> chunk = pending.subList(i, Math.min(pending.size(), i + batch));
            ctx.progress("解析静态信息 " + (i + chunk.size()) + "/" + pending.size());
            Map<String, InstrumentRow> bySymbol = new HashMap<>();
            chunk.forEach(r -> bySymbol.put(r.symbol(), r));
            List<InstrumentStatic> statics;
            try {
                statics = gateway.staticInfo(chunk.stream().map(InstrumentRow::instrument).toList()).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                ctx.partial("静态信息解析失败：" + e.getMessage());
                return "静态信息解析失败：" + e.getMessage() + "（已解析 " + resolved + "）";
            }
            for (InstrumentStatic s : statics) {
                InstrumentRow row = bySymbol.remove(s.instrument().symbol());
                if (row == null) {
                    continue;
                }
                // 实测：富途对不认识的代码也会回一条（名称"未知股票"、brokerId=0、delisted=true），不能当作已解析
                if (s.brokerId() == 0) {
                    instruments.markUnresolved(row.id());
                    unresolved++;
                } else {
                    instruments.updateStatic(row.id(), s);
                    resolved++;
                }
            }
            for (InstrumentRow miss : bySymbol.values()) {
                instruments.markUnresolved(miss.id());
                unresolved++;
            }
        }
        return "静态信息：解析 " + resolved + "，富途不认识 " + unresolved;
    }
}
