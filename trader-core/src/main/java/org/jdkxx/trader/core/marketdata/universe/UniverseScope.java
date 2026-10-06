package org.jdkxx.trader.core.marketdata.universe;

import org.jdkxx.trader.domain.IndexCode;
import org.jdkxx.trader.domain.PoolRole;
import org.jdkxx.trader.storage.marketdata.ConstituentRow;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.marketdata.PoolRepository;
import org.jdkxx.trader.storage.marketdata.PoolRow;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 各类范围的标的集合：全量（现任成分并集）、池、持仓、基准。只取富途认识（RESOLVED）且未退市的。
 * {@link #poolAndHoldings()} 含基准——采集侧一视同仁；选股侧用 {@link #candidates()} 把基准排除。
 */
public class UniverseScope {

    private final IndexConstituentRepository constituents;
    private final PoolRepository pool;
    private final InstrumentRepository instruments;

    public UniverseScope(IndexConstituentRepository constituents, PoolRepository pool, InstrumentRepository instruments) {
        this.constituents = constituents;
        this.pool = pool;
        this.instruments = instruments;
    }

    public List<InstrumentRow> universe() {
        Set<Long> ids = new LinkedHashSet<>();
        for (ConstituentRow r : constituents.currentAll()) {
            ids.add(r.instrumentId());
        }
        return usable(instruments.findByIds(ids));
    }

    public List<InstrumentRow> poolAndHoldings() {
        Set<Long> ids = pool.findAll().stream().map(PoolRow::instrumentId).collect(Collectors.toCollection(LinkedHashSet::new));
        return usable(instruments.findByIds(ids));
    }

    /** 基准标的（只采集、不选股）。 */
    public List<InstrumentRow> benchmarks() {
        return byRole(r -> r == PoolRole.BENCHMARK);
    }

    /** 候选标的：池成员减去基准。信号阶段扫描用这个。 */
    public List<InstrumentRow> candidates() {
        return byRole(r -> r != PoolRole.BENCHMARK);
    }

    private List<InstrumentRow> byRole(java.util.function.Predicate<PoolRole> keep) {
        Set<Long> ids = pool.findAll().stream().filter(p -> keep.test(p.role()))
                .map(PoolRow::instrumentId).collect(Collectors.toCollection(LinkedHashSet::new));
        return usable(instruments.findByIds(ids));
    }

    /**
     * 各指数现有成分股数。巡检用：{@link #universe()} 的分母就是这个集合，
     * 成分股被误删时分母跟着变小、完整性检查照样全绿，必须单独盯住绝对数量。
     */
    public Map<IndexCode, Integer> constituentCounts() {
        Map<IndexCode, Integer> out = new java.util.EnumMap<>(IndexCode.class);
        for (IndexCode i : IndexCode.values()) {
            out.put(i, 0);
        }
        for (ConstituentRow r : constituents.currentAll()) {
            out.merge(r.index(), 1, Integer::sum);
        }
        return out;
    }

    /**
     * 采集目标（现任成分股 ∪ 池 ∪ 持仓）的构成：名义多少、被 {@link #usable} 挡掉多少、最终应采多少。
     *
     * @param constituents 现任成分股去重数
     * @param poolExtras   池与持仓里<b>不在</b>成分股里的那些（基准、现金管理工具这类）
     * @param missingRows  有 id 却查不到标的行的个数（外键在，正常恒为 0；非 0 说明算式对不上，要打出来）
     * @param unusable     被挡掉的行（非 RESOLVED 或已退市），逐只点名用
     * @param expected     最终应采的行
     */
    public record TargetBreakdown(int constituents, int poolExtras, int missingRows,
                                  List<InstrumentRow> unusable, List<InstrumentRow> expected) {

        /** 名义目标数：成分股 + 池与持仓另加的。 */
        public int nominal() {
            return constituents + poolExtras;
        }
    }

    /**
     * 把 {@code completeness} 分母的构成一次算清。
     *
     * <p><b>为什么要有这个</b>：分母悄悄变小是这个项目踩过三次的坑——成分股被误删（纯集合差）、
     * 标的被标 UNRESOLVED（批次级恢复会主动写，见 {@link UnknownSymbolGuard}）、当天停牌。
     * 三次都是<b>分子分母一起少一、完整性检查照样全绿</b>，人什么也看不到。
     * 前两次各补了一条伴随检查；与其再加第三条，不如让分母的算式本身永远摆在明面上：
     * 审计把名义、各项减数、应采、实采<b>不管绿不绿都打出来</b>，缩水就不可能隐身。
     */
    public TargetBreakdown targetBreakdown() {
        Set<Long> constituentIds = constituentIds();
        Set<Long> all = new LinkedHashSet<>(constituentIds);
        pool.findAll().forEach(p -> all.add(p.instrumentId()));
        List<InstrumentRow> rows = instruments.findByIds(all);
        List<InstrumentRow> expected = usable(rows);
        List<InstrumentRow> unusable = rows.stream().filter(r -> !isUsable(r)).toList();
        return new TargetBreakdown(constituentIds.size(), all.size() - constituentIds.size(),
                all.size() - rows.size(), unusable, expected);
    }

    private Set<Long> constituentIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (ConstituentRow r : constituents.currentAll()) {
            ids.add(r.instrumentId());
        }
        return ids;
    }

    public Map<Long, PoolRole> roles() {
        return pool.findAll().stream().collect(Collectors.toMap(PoolRow::instrumentId, PoolRow::role));
    }

    private static List<InstrumentRow> usable(List<InstrumentRow> rows) {
        return rows.stream().filter(UniverseScope::isUsable).toList();
    }

    private static boolean isUsable(InstrumentRow r) {
        return "RESOLVED".equals(r.resolveStatus()) && !r.delisted();
    }
}
