package org.jdkxx.trader.storage.selection;

import org.jdkxx.trader.domain.selection.SplitAdjustedBar;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 盈透拆股调整日线（§25.6）。与 {@code daily_bar} 口径相反（那边不复权），不得混读。
 * 一只标的整段替换：拆股基准随抓取日变化，同一只里不能混着两个基准。
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionPriceBarRepository {

    public record Coverage(long rows, long instruments, LocalDate earliest, LocalDate latest) {
    }

    private final JdbcTemplate jdbc;

    public SelectionPriceBarRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public int replaceAll(long instrumentId, List<SplitAdjustedBar> bars, LocalDate splitBasisDate) {
        jdbc.update("DELETE FROM selection_price_bar WHERE instrument_id = ?", instrumentId);
        List<Object[]> args = new ArrayList<>(bars.size());
        for (SplitAdjustedBar b : bars) {
            args.add(new Object[] {instrumentId, Date.valueOf(b.tradeDate()), b.open(), b.high(), b.low(), b.close(), b.volume(),
                    Date.valueOf(splitBasisDate)});
        }
        if (!args.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO selection_price_bar (instrument_id, trade_date, open, high, low, close, volume, split_basis_date)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", args);
        }
        return args.size();
    }

    public List<SplitAdjustedBar> find(long instrumentId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT trade_date, open, high, low, close, volume FROM selection_price_bar
                WHERE instrument_id = ? AND trade_date BETWEEN ? AND ? ORDER BY trade_date""",
                (rs, n) -> new SplitAdjustedBar(rs.getDate("trade_date").toLocalDate(), rs.getBigDecimal("open"),
                        rs.getBigDecimal("high"), rs.getBigDecimal("low"), rs.getBigDecimal("close"), rs.getLong("volume")),
                instrumentId, Date.valueOf(from), Date.valueOf(to));
    }

    /** 没有任何行时 earliest / latest 为 null（聚合查询总有一行，这里允许 null）。 */
    public Coverage coverage() {
        return jdbc.queryForObject("""
                SELECT count(*) AS n, count(DISTINCT instrument_id) AS k, min(trade_date) AS lo, max(trade_date) AS hi
                FROM selection_price_bar""",
                (rs, n) -> new Coverage(rs.getLong("n"), rs.getLong("k"),
                        rs.getDate("lo") == null ? null : rs.getDate("lo").toLocalDate(),
                        rs.getDate("hi") == null ? null : rs.getDate("hi").toLocalDate()));
    }
}
