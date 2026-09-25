package org.jdkxx.trader.storage.marketdata;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class DailyBarRepository {

    /** 全库覆盖统计。 */
    public record Coverage(long rows, long instruments, LocalDate earliest, LocalDate latest) {
    }

    public record InstrumentCoverage(long instrumentId, long rows, LocalDate earliest, LocalDate latest) {
    }

    private final JdbcTemplate jdbc;

    public DailyBarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入（ON CONFLICT 覆盖），返回条数。 */
    public int upsertAll(long instrumentId, List<DailyBar> bars, String source) {
        if (bars.isEmpty()) {
            return 0;
        }
        List<Object[]> args = new ArrayList<>(bars.size());
        for (DailyBar b : bars) {
            args.add(new Object[] {instrumentId, Date.valueOf(b.tradeDate()), b.open(), b.high(), b.low(), b.close(),
                    b.lastClose(), b.volume(), b.turnover(), b.turnoverRate(), b.changeRate(), b.pe(), b.blank(), source});
        }
        jdbc.batchUpdate("""
                INSERT INTO daily_bar (instrument_id, trade_date, open, high, low, close, last_close, volume, turnover,
                                       turnover_rate, change_rate, pe, blank, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (instrument_id, trade_date) DO UPDATE SET
                    open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low, close = EXCLUDED.close,
                    last_close = EXCLUDED.last_close, volume = EXCLUDED.volume, turnover = EXCLUDED.turnover,
                    turnover_rate = EXCLUDED.turnover_rate, change_rate = EXCLUDED.change_rate, pe = EXCLUDED.pe,
                    blank = EXCLUDED.blank, source = EXCLUDED.source, fetched_at = now()""", args);
        return bars.size();
    }

    public List<DailyBar> find(Instrument instrument, long instrumentId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT trade_date, open, high, low, close, last_close, volume, turnover, turnover_rate, change_rate, pe, blank
                FROM daily_bar WHERE instrument_id = ? AND trade_date BETWEEN ? AND ? ORDER BY trade_date""",
                (rs, i) -> new DailyBar(instrument, rs.getDate("trade_date").toLocalDate(), rs.getBigDecimal("open"),
                        rs.getBigDecimal("high"), rs.getBigDecimal("low"), rs.getBigDecimal("close"), rs.getBigDecimal("last_close"),
                        rs.getLong("volume"), rs.getBigDecimal("turnover"), rs.getBigDecimal("turnover_rate"),
                        rs.getBigDecimal("change_rate"), rs.getBigDecimal("pe"), rs.getBoolean("blank")),
                instrumentId, Date.valueOf(from), Date.valueOf(to));
    }

    /** 多只标的一次取出（按标的分组、日期升序）；ids 里没有 K 线的标的不出现在结果里。 */
    public Map<Long, List<DailyBar>> findMany(Map<Long, Instrument> instruments, LocalDate from, LocalDate to) {
        Map<Long, List<DailyBar>> out = new HashMap<>();
        if (instruments.isEmpty()) {
            return out;
        }
        jdbc.query("""
                SELECT instrument_id, trade_date, open, high, low, close, last_close, volume, turnover, turnover_rate, change_rate, pe, blank
                FROM daily_bar WHERE instrument_id = ANY (?) AND trade_date BETWEEN ? AND ? ORDER BY instrument_id, trade_date""",
                rs -> {
                    long id = rs.getLong("instrument_id");
                    out.computeIfAbsent(id, k -> new java.util.ArrayList<>()).add(new DailyBar(instruments.get(id),
                            rs.getDate("trade_date").toLocalDate(), rs.getBigDecimal("open"), rs.getBigDecimal("high"),
                            rs.getBigDecimal("low"), rs.getBigDecimal("close"), rs.getBigDecimal("last_close"), rs.getLong("volume"),
                            rs.getBigDecimal("turnover"), rs.getBigDecimal("turnover_rate"), rs.getBigDecimal("change_rate"),
                            rs.getBigDecimal("pe"), rs.getBoolean("blank")));
                },
                instruments.keySet().toArray(Long[]::new), Date.valueOf(from), Date.valueOf(to));
        return out;
    }

    public Optional<LocalDate> latestDate(long instrumentId) {
        Date d = jdbc.query("SELECT max(trade_date) FROM daily_bar WHERE instrument_id = ?", rs -> rs.next() ? rs.getDate(1) : null, instrumentId);
        return Optional.ofNullable(d).map(Date::toLocalDate);
    }

    /**
     * 每只标的<b>已落定</b>的最新交易日。已落定 = 写入时这根 K 线已经收盘：交易日早于写入日（按 zone），
     * 或写入发生在当天 settledAt 之后。
     *
     * <p>收盘落定前写入的当天那根不算：它是盘中价，不能当成"最新已经有了"而跳过重拉
     * （2.0.2 前盘中深度回补会留下这种行，当晚增量看到最新日期已到就不再补）。
     */
    public Map<Long, LocalDate> latestSettledDates(ZoneId zone, LocalTime settledAt) {
        Map<Long, LocalDate> m = new HashMap<>();
        jdbc.query("""
                SELECT instrument_id, max(trade_date) AS d FROM daily_bar
                WHERE trade_date < (fetched_at AT TIME ZONE CAST(? AS text))::date
                   OR (fetched_at AT TIME ZONE CAST(? AS text))::time >= CAST(? AS time)
                GROUP BY instrument_id""",
                rs -> { m.put(rs.getLong("instrument_id"), rs.getDate("d").toLocalDate()); },
                zone.getId(), zone.getId(), settledAt.toString());
        return m;
    }

    public record UnsettledBar(long instrumentId, LocalDate tradeDate, Instant fetchedAt) {
    }

    /** 收盘落定前写入的当天（或更晚日期）的 K 线；条件与 {@link #latestSettledDates} 互补。 */
    public List<UnsettledBar> unsettledBars(ZoneId zone, LocalTime settledAt, int limit) {
        return jdbc.query("""
                SELECT instrument_id, trade_date, fetched_at FROM daily_bar
                WHERE trade_date >= (fetched_at AT TIME ZONE CAST(? AS text))::date
                  AND (fetched_at AT TIME ZONE CAST(? AS text))::time < CAST(? AS time)
                ORDER BY trade_date DESC, instrument_id
                LIMIT ?""",
                (rs, i) -> new UnsettledBar(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getTimestamp(3).toInstant()),
                zone.getId(), zone.getId(), settledAt.toString(), Math.max(1, Math.min(limit, 1000)));
    }

    /** 最近一根 K 线的收盘价。 */
    public Optional<java.math.BigDecimal> latestClose(long instrumentId) {
        return jdbc.query("SELECT close FROM daily_bar WHERE instrument_id = ? ORDER BY trade_date DESC LIMIT 1",
                (rs, n) -> rs.getBigDecimal("close"), instrumentId).stream().findFirst();
    }

    /**
     * 日 K 市盈率的中位数，只算<b>正值</b>。券商在亏损期与基金上给 0（2026-09-19 实测 INTC 日 K 当前 0、
     * 估值快照 -49.99；SPY 日 K 全是 0），0 是"无数据"不是真值，混进分位统计会把中位数拉低。
     */
    public Optional<Double> peMedian(long instrumentId, LocalDate from) {
        // HAVING 把"一条正 PE 都没有"下推给数据库：没有就<b>不返回行</b>。
        // 3.1.0 里用 RowMapper 返回 null 表示没有，结果 findFirst() 在 Optional.of(null) 上抛 NPE——
        // 生产上 SPY（日 K 的 pe 全是 0）因此 500。单测里仓库是替身，这条路径只有真实数据才走得到。
        return jdbc.query("""
                SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY pe) AS median
                FROM daily_bar WHERE instrument_id = ? AND trade_date >= ? AND pe > 0
                HAVING count(*) > 0
                """, (rs, n) -> rs.getDouble("median"),
                instrumentId, java.sql.Date.valueOf(from)).stream().findFirst();
    }

    public Coverage coverage() {
        return jdbc.queryForObject("SELECT count(*) AS rows, count(DISTINCT instrument_id) AS instruments, min(trade_date) AS mn, max(trade_date) AS mx FROM daily_bar",
                (rs, i) -> new Coverage(rs.getLong("rows"), rs.getLong("instruments"),
                        rs.getDate("mn") == null ? null : rs.getDate("mn").toLocalDate(),
                        rs.getDate("mx") == null ? null : rs.getDate("mx").toLocalDate()));
    }

    /** 指定交易日这些标的的收盘价（不复权）；没有当日 K 线的不在结果里。 */
    public Map<Long, java.math.BigDecimal> closesOn(LocalDate date, Collection<Long> instrumentIds) {
        Map<Long, java.math.BigDecimal> m = new HashMap<>();
        if (instrumentIds.isEmpty()) {
            return m;
        }
        jdbc.query("SELECT instrument_id, close FROM daily_bar WHERE trade_date = ? AND instrument_id = ANY (?)",
                rs -> { m.put(rs.getLong(1), rs.getBigDecimal(2)); }, Date.valueOf(date), (Object) instrumentIds.toArray(Long[]::new));
        return m;
    }

    // ------------------------------------------------------------------ 审计查询

    /** 指定交易日有 K 线的标的 id。 */
    public java.util.Set<Long> instrumentIdsWithBarOn(LocalDate date) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        jdbc.query("SELECT instrument_id FROM daily_bar WHERE trade_date = ?", rs -> { ids.add(rs.getLong(1)); }, Date.valueOf(date));
        return ids;
    }

    public record ContinuityIssue(long instrumentId, LocalDate tradeDate, java.math.BigDecimal lastClose, java.math.BigDecimal prevClose) {
    }

    /**
     * 区间内 last_close 与上一根 close 不等的记录（不等 = 中间漏了交易日，或券商数据有误）。
     *
     * <p>内层把下界往前挪 7 天只是为了给 {@code lag()} 垫一根前值；
     * 外层<b>必须再按 from 过滤一次</b>，否则会把调用者没要的那 7 天里的问题
     * 也当成窗口内的报出来（2026-09-25 全项目审查发现）。
     */
    public List<ContinuityIssue> continuityIssues(LocalDate from, LocalDate to, int limit) {
        return jdbc.query("""
                SELECT instrument_id, trade_date, last_close, prev FROM (
                    SELECT instrument_id, trade_date, last_close,
                           lag(close) OVER (PARTITION BY instrument_id ORDER BY trade_date) AS prev
                    FROM daily_bar WHERE trade_date BETWEEN ? AND ?
                ) x WHERE prev IS NOT NULL AND last_close IS NOT NULL AND abs(last_close - prev) > 0.0005
                  AND trade_date >= ?
                ORDER BY trade_date DESC, instrument_id LIMIT ?""",
                (rs, i) -> new ContinuityIssue(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3), rs.getBigDecimal(4)),
                Date.valueOf(from.minusDays(7)), Date.valueOf(to), Date.valueOf(from), Math.max(1, limit));
    }

    public record DaySanity(long bars, long ohlcInconsistent, long nonPositiveClose, long blank, long zeroVolume, long nullTurnover) {
    }

    public DaySanity sanityOn(LocalDate date) {
        return jdbc.queryForObject("""
                SELECT count(*) AS bars,
                       count(*) FILTER (WHERE high < low OR close > high OR close < low OR open > high OR open < low) AS ohlc,
                       count(*) FILTER (WHERE close <= 0) AS nonpos,
                       count(*) FILTER (WHERE blank) AS blank,
                       count(*) FILTER (WHERE volume = 0) AS zerovol,
                       count(*) FILTER (WHERE turnover IS NULL) AS nullturn
                FROM daily_bar WHERE trade_date = ?""",
                (rs, i) -> new DaySanity(rs.getLong("bars"), rs.getLong("ohlc"), rs.getLong("nonpos"), rs.getLong("blank"),
                        rs.getLong("zerovol"), rs.getLong("nullturn")), Date.valueOf(date));
    }

    public Optional<LocalDate> maxTradeDate() {
        Date d = jdbc.query("SELECT max(trade_date) FROM daily_bar", rs -> rs.next() ? rs.getDate(1) : null);
        return Optional.ofNullable(d).map(Date::toLocalDate);
    }

    /**
     * 指定标的在 [from, to) 内、<b>至少 minInstruments 只同时有 K 线</b>的交易日（去重升序）。
     * 用来反推券商给不出的早年交易日历：传入的应是大盘股（池/持仓/基准），它们每个交易日都有成交。
     *
     * <p>阈值不能省：实测富途对 SPY 在三个美股假日（2011-07-04 独立日、2012-04-06 耶稣受难日、
     * 2012-05-28 阵亡将士纪念日）给出了脏 K 线（成交额 0、最低价明显异常），只按"有没有 K 线"取并集
     * 会把这些假日当成交易日。真实交易日有 13 只以上同时成交，脏数据只有 1 只，中间断层很宽，
     * 阈值取 2 就够，且离两边都远。
     */
    public List<LocalDate> distinctTradeDates(Collection<Long> instrumentIds, LocalDate from, LocalDate to,
                                              int minInstruments) {
        if (instrumentIds.isEmpty()) {
            return List.of();
        }
        String ids = instrumentIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        int min = Math.max(1, Math.min(minInstruments, instrumentIds.size()));
        return jdbc.query("SELECT trade_date FROM daily_bar WHERE instrument_id IN (" + ids
                        + ") AND trade_date >= ? AND trade_date < ? GROUP BY trade_date HAVING count(*) >= ? ORDER BY trade_date",
                (rs, i) -> rs.getDate(1).toLocalDate(), Date.valueOf(from), Date.valueOf(to), min);
    }

    /**
     * 对照交易日历找缺口：标的在自己有数据的区间内、日历上有而库里没有的交易日。
     *
     * <p>这类缺口<b>前收连续性检查发现不了</b>——实测富途的 SPY 缺了 26 个交易日（2009~2012），
     * 而它给的 last_close 与缺口自洽，比对前收完全看不出来。只有拿日历比对才查得到。
     *
     * @param from 起始日（含），只统计标的自身有数据的区间与之相交的部分
     * @param to   截止日（含）
     */
    public List<InstrumentGap> gaps(LocalDate from, LocalDate to, int limit) {
        return gaps(from, to, limit, null);
    }

    /**
     * @param onlyInstruments 只看这些标的；传 null 表示不限。
     *     <b>过滤必须下推到 SQL</b>：调用方先取 {@code LIMIT n} 再在内存里按标的过滤，
     *     等于「先截断再筛选」——前 n 条恰好都不是关心的标的时结果是空的，
     *     巡检会报「没有缺口」（2026-09-25 全项目审查发现）。
     */
    public List<InstrumentGap> gaps(LocalDate from, LocalDate to, int limit, java.util.Collection<Long> onlyInstruments) {
        if (onlyInstruments != null && onlyInstruments.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        String filter = "";
        if (onlyInstruments != null) {
            filter = " WHERE instrument_id = ANY (?)";
            args.add(onlyInstruments.toArray(Long[]::new));
        }
        args.add(Date.valueOf(from));
        args.add(Date.valueOf(to));
        args.add(limit);
        return jdbc.query("""
                WITH span AS (
                    SELECT instrument_id, min(trade_date) AS first_bar, max(trade_date) AS last_bar
                    FROM daily_bar%s GROUP BY instrument_id)
                SELECT s.instrument_id,
                       count(*) AS missing,
                       min(t.trade_date) AS first_missing,
                       max(t.trade_date) AS last_missing
                FROM span s
                JOIN trading_day t ON t.market = 'US'
                     AND t.trade_date BETWEEN greatest(s.first_bar, ?) AND least(s.last_bar, ?)
                WHERE NOT EXISTS (
                    SELECT 1 FROM daily_bar b
                    WHERE b.instrument_id = s.instrument_id AND b.trade_date = t.trade_date)
                GROUP BY s.instrument_id
                ORDER BY missing DESC
                LIMIT ?""".formatted(filter),
                (rs, i) -> new InstrumentGap(rs.getLong("instrument_id"), rs.getLong("missing"),
                        rs.getDate("first_missing").toLocalDate(), rs.getDate("last_missing").toLocalDate()),
                args.toArray());
    }

    /** 一只标的对照日历缺失的交易日。 */
    public record InstrumentGap(long instrumentId, long missing, LocalDate firstMissing, LocalDate lastMissing) {
    }

    /**
     * 幽灵 K 线：落在日历覆盖区间内、却不在交易日历里的行。
     *
     * <p>实测富途给 SPY 在三个美股假日留了脏 K 线（2011-07-04、2012-04-06、2012-05-28，成交额都是 0，
     * 独立日那根还凭空造出 15% 的日内暴跌）。日历已被独立验证在该区间内完全正确，
     * 所以"不在日历里"等价于"不该存在"。区间外（日历没覆盖的年份）一律不碰。
     */
    public List<PhantomBar> phantomBars(int limit) {
        return jdbc.query("""
                WITH span AS (SELECT min(trade_date) AS lo, max(trade_date) AS hi FROM trading_day WHERE market = 'US')
                SELECT b.instrument_id, b.trade_date, b.open, b.high, b.low, b.close, b.volume, b.turnover
                FROM daily_bar b, span s
                WHERE b.trade_date BETWEEN s.lo AND s.hi
                  AND NOT EXISTS (SELECT 1 FROM trading_day t WHERE t.market = 'US' AND t.trade_date = b.trade_date)
                ORDER BY b.trade_date
                LIMIT ?""",
                (rs, i) -> new PhantomBar(rs.getLong("instrument_id"), rs.getDate("trade_date").toLocalDate(),
                        rs.getBigDecimal("open"), rs.getBigDecimal("high"), rs.getBigDecimal("low"),
                        rs.getBigDecimal("close"), rs.getLong("volume"), rs.getBigDecimal("turnover")),
                Math.max(1, Math.min(limit, 1000)));
    }

    /**
     * 一次订正的上限：试跑列多少、订正就删多少。放在这里是因为审计与订正都要提到它，
     * 而两边本来就都依赖本类——让审计去依赖 {@code MarketDataFacade} 是反向的。
     */
    public static final int PHANTOM_BATCH = 200;

    /**
     * 幽灵 K 线<b>总数</b>，不设上限。{@link #phantomBars} 是带上限的清单，
     * <b>别拿它的条数当总数</b>——3.1.1 前审计用 {@code phantomBars(20).size()} 当条数、
     * 订正试跑用 {@code phantomBars(200).size()}，而删除按条件全删：
     * 三处口径互不相同，你看到 20 条、它可能删几千条（2026-09-25 全项目审查发现）。
     */
    public int phantomBarCount() {
        Integer n = jdbc.queryForObject("""
                WITH span AS (SELECT min(trade_date) AS lo, max(trade_date) AS hi FROM trading_day WHERE market = 'US')
                SELECT count(*) FROM daily_bar b, span s
                WHERE b.trade_date BETWEEN s.lo AND s.hi
                  AND NOT EXISTS (SELECT 1 FROM trading_day t WHERE t.market = 'US' AND t.trade_date = b.trade_date)""",
                Integer.class);
        return n == null ? 0 : n;
    }

    /**
     * 只删<b>传进来的这些行</b>，返回删除条数；空清单直接返回 0，不发 SQL。
     *
     * <p>不提供"照条件全删"的版本：试跑给你看什么就删什么，多出来的留到下一轮——
     * 删 K 线要重新回补才能恢复，而回补又会把券商的脏 K 线拉回来（见坑表），所以宁可多跑几轮。
     * 日历本身坏掉时全库都会被判成幽灵，这时"只删给你看过的那些"就是最后一道闸。
     *
     * <p>仍然带上幽灵条件：清单是上一步查出来的，两步之间日历可能被回补过，
     * 那些行就不再是幽灵了，不能删。
     */
    public int deletePhantomBars(List<PhantomBar> targets) {
        if (targets == null || targets.isEmpty()) {
            return 0;
        }
        StringBuilder values = new StringBuilder();
        List<Object> args = new java.util.ArrayList<>();
        for (PhantomBar t : targets) {
            values.append(values.isEmpty() ? "" : ", ").append("(?::bigint, ?::date)");
            args.add(t.instrumentId());
            args.add(java.sql.Date.valueOf(t.tradeDate()));
        }
        return jdbc.update("""
                WITH span AS (SELECT min(trade_date) AS lo, max(trade_date) AS hi FROM trading_day WHERE market = 'US')
                DELETE FROM daily_bar b
                USING span s
                WHERE b.trade_date BETWEEN s.lo AND s.hi
                  AND NOT EXISTS (SELECT 1 FROM trading_day t WHERE t.market = 'US' AND t.trade_date = b.trade_date)
                  AND EXISTS (SELECT 1 FROM (VALUES %s) AS v(id, d)
                              WHERE v.id = b.instrument_id AND v.d = b.trade_date)""".formatted(values),
                args.toArray());
    }

    public record PhantomBar(long instrumentId, LocalDate tradeDate, java.math.BigDecimal open, java.math.BigDecimal high,
                             java.math.BigDecimal low, java.math.BigDecimal close, long volume,
                             java.math.BigDecimal turnover) {
    }

    /** 单只的真实覆盖（条数与区间）；一根都没有时为空。 */
    public Optional<InstrumentCoverage> coverage(long instrumentId) {
        return jdbc.query("SELECT count(*) AS rows, min(trade_date) AS mn, max(trade_date) AS mx FROM daily_bar WHERE instrument_id = ?",
                (rs, i) -> rs.getLong("rows") == 0 ? null
                        : new InstrumentCoverage(instrumentId, rs.getLong("rows"), rs.getDate("mn").toLocalDate(), rs.getDate("mx").toLocalDate()),
                instrumentId).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public List<InstrumentCoverage> coverageByInstrument() {
        return jdbc.query("SELECT instrument_id, count(*) AS rows, min(trade_date) AS mn, max(trade_date) AS mx FROM daily_bar GROUP BY instrument_id",
                (rs, i) -> new InstrumentCoverage(rs.getLong("instrument_id"), rs.getLong("rows"),
                        rs.getDate("mn").toLocalDate(), rs.getDate("mx").toLocalDate()));
    }
}
