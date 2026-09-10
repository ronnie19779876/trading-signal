package org.jdkxx.trader.storage.marketdata;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
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

    public Optional<LocalDate> latestDate(long instrumentId) {
        Date d = jdbc.query("SELECT max(trade_date) FROM daily_bar WHERE instrument_id = ?", rs -> rs.next() ? rs.getDate(1) : null, instrumentId);
        return Optional.ofNullable(d).map(Date::toLocalDate);
    }

    public Map<Long, LocalDate> latestDates() {
        Map<Long, LocalDate> m = new HashMap<>();
        jdbc.query("SELECT instrument_id, max(trade_date) AS d FROM daily_bar GROUP BY instrument_id",
                rs -> { m.put(rs.getLong("instrument_id"), rs.getDate("d").toLocalDate()); });
        return m;
    }

    public Coverage coverage() {
        return jdbc.queryForObject("SELECT count(*) AS rows, count(DISTINCT instrument_id) AS instruments, min(trade_date) AS mn, max(trade_date) AS mx FROM daily_bar",
                (rs, i) -> new Coverage(rs.getLong("rows"), rs.getLong("instruments"),
                        rs.getDate("mn") == null ? null : rs.getDate("mn").toLocalDate(),
                        rs.getDate("mx") == null ? null : rs.getDate("mx").toLocalDate()));
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

    /** 区间内 last_close 与上一根 close 不等的记录（不等 = 中间漏了交易日，或券商数据有误）。 */
    public List<ContinuityIssue> continuityIssues(LocalDate from, LocalDate to, int limit) {
        return jdbc.query("""
                SELECT instrument_id, trade_date, last_close, prev FROM (
                    SELECT instrument_id, trade_date, last_close,
                           lag(close) OVER (PARTITION BY instrument_id ORDER BY trade_date) AS prev
                    FROM daily_bar WHERE trade_date BETWEEN ? AND ?
                ) x WHERE prev IS NOT NULL AND last_close IS NOT NULL AND abs(last_close - prev) > 0.0005
                ORDER BY trade_date DESC, instrument_id LIMIT ?""",
                (rs, i) -> new ContinuityIssue(rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getBigDecimal(3), rs.getBigDecimal(4)),
                Date.valueOf(from.minusDays(7)), Date.valueOf(to), Math.max(1, limit));
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
        return jdbc.query("""
                WITH span AS (
                    SELECT instrument_id, min(trade_date) AS first_bar, max(trade_date) AS last_bar
                    FROM daily_bar GROUP BY instrument_id)
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
                LIMIT ?""",
                (rs, i) -> new InstrumentGap(rs.getLong("instrument_id"), rs.getLong("missing"),
                        rs.getDate("first_missing").toLocalDate(), rs.getDate("last_missing").toLocalDate()),
                Date.valueOf(from), Date.valueOf(to), limit);
    }

    /** 一只标的对照日历缺失的交易日。 */
    public record InstrumentGap(long instrumentId, long missing, LocalDate firstMissing, LocalDate lastMissing) {
    }

    public List<InstrumentCoverage> coverageByInstrument() {
        return jdbc.query("SELECT instrument_id, count(*) AS rows, min(trade_date) AS mn, max(trade_date) AS mx FROM daily_bar GROUP BY instrument_id",
                (rs, i) -> new InstrumentCoverage(rs.getLong("instrument_id"), rs.getLong("rows"),
                        rs.getDate("mn").toLocalDate(), rs.getDate("mx").toLocalDate()));
    }
}
