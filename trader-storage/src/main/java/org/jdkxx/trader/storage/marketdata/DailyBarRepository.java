package org.jdkxx.trader.storage.marketdata;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
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

    public List<InstrumentCoverage> coverageByInstrument() {
        return jdbc.query("SELECT instrument_id, count(*) AS rows, min(trade_date) AS mn, max(trade_date) AS mx FROM daily_bar GROUP BY instrument_id",
                (rs, i) -> new InstrumentCoverage(rs.getLong("instrument_id"), rs.getLong("rows"),
                        rs.getDate("mn").toLocalDate(), rs.getDate("mx").toLocalDate()));
    }
}
