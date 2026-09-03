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

    public Optional<LocalDate> latest(Market market) {
        Date d = jdbc.query("SELECT max(trade_date) FROM trading_day WHERE market = ?", rs -> rs.next() ? rs.getDate(1) : null, market.name());
        return Optional.ofNullable(d).map(Date::toLocalDate);
    }
}
