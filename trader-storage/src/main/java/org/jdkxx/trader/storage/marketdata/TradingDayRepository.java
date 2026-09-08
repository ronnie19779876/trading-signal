package org.jdkxx.trader.storage.marketdata;

import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.TradingDay;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class TradingDayRepository {

    private final JdbcTemplate jdbc;

    public TradingDayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertAll(List<TradingDay> days) {
        if (days.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>();
        for (TradingDay d : days) {
            args.add(new Object[] {d.market().name(), Date.valueOf(d.date()), d.kind()});
        }
        jdbc.batchUpdate("INSERT INTO trading_day (market, trade_date, kind) VALUES (?, ?, ?) ON CONFLICT (market, trade_date) DO UPDATE SET kind = EXCLUDED.kind", args);
    }

    public List<LocalDate> between(Market market, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT trade_date FROM trading_day WHERE market = ? AND trade_date BETWEEN ? AND ? ORDER BY trade_date",
                (rs, i) -> rs.getDate(1).toLocalDate(), market.name(), Date.valueOf(from), Date.valueOf(to));
    }

    public Optional<LocalDate> latestOnOrBefore(Market market, LocalDate date) {
        Date d = jdbc.query("SELECT max(trade_date) FROM trading_day WHERE market = ? AND trade_date <= ?",
                rs -> rs.next() ? rs.getDate(1) : null, market.name(), Date.valueOf(date));
        return Optional.ofNullable(d).map(Date::toLocalDate);
    }

    /** 日历里是否明确有这一天（有 = 交易日）。 */
    public boolean isTradingDay(Market market, LocalDate date) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM trading_day WHERE market = ? AND trade_date = ?",
                Integer.class, market.name(), Date.valueOf(date));
        return n != null && n > 0;
    }

    /**
     * 日历是否覆盖了这一天：前后都有交易日记录。
     * 日历只装最近一段时间，覆盖不到的日期不能凭"不在日历里"判成休市。
     */
    public boolean covers(Market market, LocalDate date) {
        Integer n = jdbc.queryForObject("""
                SELECT CASE WHEN EXISTS (SELECT 1 FROM trading_day WHERE market = ? AND trade_date <= ?)
                             AND EXISTS (SELECT 1 FROM trading_day WHERE market = ? AND trade_date >= ?)
                            THEN 1 ELSE 0 END""",
                Integer.class, market.name(), Date.valueOf(date), market.name(), Date.valueOf(date));
        return n != null && n == 1;
    }

    public Optional<LocalDate> latest(Market market) {
        Date d = jdbc.query("SELECT max(trade_date) FROM trading_day WHERE market = ?", rs -> rs.next() ? rs.getDate(1) : null, market.name());
        return Optional.ofNullable(d).map(Date::toLocalDate);
    }
}
