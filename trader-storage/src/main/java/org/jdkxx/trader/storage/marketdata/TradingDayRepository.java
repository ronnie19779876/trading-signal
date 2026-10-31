package org.jdkxx.trader.storage.marketdata;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.TradingDay;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Trading calendar ({@code trading_day}). A row means "this market trades on this date"; absence
 * only means "closed" for dates the calendar covers (see {@link #covers}). Rows come from two
 * sources: {@code FUTU} (the broker's calendar, from about 2016-09) and {@code DERIVED}
 * (reconstructed from daily bars for earlier years).
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class TradingDayRepository {

  private final JdbcTemplate jdbc;

  public TradingDayRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The broker's calendar, written as an overwrite (a DERIVED row for the same date becomes FUTU).
   *
   * <p>Upsert only: a date that the broker stops returning (for example an unscheduled closure
   * announced after the date was first fetched as a future day) is never removed by this method.
   */
  public void upsertAll(List<TradingDay> days) {
    if (days.isEmpty()) {
      return;
    }
    List<Object[]> args = new ArrayList<>();
    for (TradingDay d : days) {
      args.add(new Object[] {d.market().name(), Date.valueOf(d.date()), d.kind()});
    }
    jdbc.batchUpdate(
        "INSERT INTO trading_day (market, trade_date, kind, source) VALUES (?, ?, ?, 'FUTU') "
            + "ON CONFLICT (market, trade_date) DO UPDATE SET kind = EXCLUDED.kind, source = 'FUTU'",
        args);
  }

  /**
   * Calendar reconstructed from daily bars; only fills dates that are missing, and returns how many
   * were inserted. The broker's calendar only goes back to 2016-09, so earlier years are
   * reconstructed. Existing FUTU rows are never touched; otherwise, after the daily increment
   * refreshes the last 60 days, the reconstructed range would be rewritten again and again.
   */
  public int insertDerived(Market market, List<LocalDate> dates) {
    if (dates.isEmpty()) {
      return 0;
    }
    List<Object[]> args = new ArrayList<>();
    for (LocalDate d : dates) {
      args.add(new Object[] {market.name(), Date.valueOf(d)});
    }
    int[] n =
        jdbc.batchUpdate(
            "INSERT INTO trading_day (market, trade_date, kind, source) VALUES (?, ?, 0, 'DERIVED') "
                + "ON CONFLICT (market, trade_date) DO NOTHING",
            args);
    int inserted = 0;
    // A batch may report SUCCESS_NO_INFO (-2) instead of a row count; only positive counts are
    // added.
    for (int i : n) {
      inserted += i > 0 ? i : 0;
    }
    return inserted;
  }

  /** Calendar coverage: earliest, latest, total days, and how many from each source. */
  public Coverage coverage(Market market) {
    return jdbc.query(
        """
                SELECT min(trade_date) AS earliest, max(trade_date) AS latest, count(*) AS days,
                       count(*) FILTER (WHERE source = 'FUTU')    AS from_broker,
                       count(*) FILTER (WHERE source = 'DERIVED') AS derived
                FROM trading_day WHERE market = ?""",
        rs ->
            rs.next()
                ? new Coverage(
                    rs.getDate("earliest") == null ? null : rs.getDate("earliest").toLocalDate(),
                    rs.getDate("latest") == null ? null : rs.getDate("latest").toLocalDate(),
                    rs.getLong("days"),
                    rs.getLong("from_broker"),
                    rs.getLong("derived"))
                : new Coverage(null, null, 0, 0, 0),
        market.name());
  }

  public List<Day> list(Market market, LocalDate from, LocalDate to) {
    return jdbc.query(
        "SELECT trade_date, kind, source FROM trading_day WHERE market = ? AND trade_date BETWEEN ? AND ? ORDER BY trade_date",
        (rs, i) ->
            new Day(
                rs.getDate("trade_date").toLocalDate(), rs.getInt("kind"), rs.getString("source")),
        market.name(),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  public record Coverage(
      LocalDate earliest, LocalDate latest, long days, long fromBroker, long derived) {}

  public record Day(LocalDate date, int kind, String source) {}

  public List<LocalDate> between(Market market, LocalDate from, LocalDate to) {
    return jdbc.query(
        "SELECT trade_date FROM trading_day WHERE market = ? AND trade_date BETWEEN ? AND ? ORDER BY trade_date",
        (rs, i) -> rs.getDate(1).toLocalDate(),
        market.name(),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  public Optional<LocalDate> latestOnOrBefore(Market market, LocalDate date) {
    Date d =
        jdbc.query(
            "SELECT max(trade_date) FROM trading_day WHERE market = ? AND trade_date <= ?",
            rs -> rs.next() ? rs.getDate(1) : null,
            market.name(),
            Date.valueOf(date));
    return Optional.ofNullable(d).map(Date::toLocalDate);
  }

  /** Whether the calendar explicitly contains this date (present = trading day). */
  public boolean isTradingDay(Market market, LocalDate date) {
    Integer n =
        jdbc.queryForObject(
            "SELECT count(*) FROM trading_day WHERE market = ? AND trade_date = ?",
            Integer.class,
            market.name(),
            Date.valueOf(date));
    return n != null && n > 0;
  }

  /**
   * Whether the calendar covers this date: there are trading-day rows on or before it and on or
   * after it. The calendar only holds a bounded range, so a date outside it must not be judged
   * "market closed" merely because it is not in the calendar.
   */
  public boolean covers(Market market, LocalDate date) {
    Integer n =
        jdbc.queryForObject(
            """
                SELECT CASE WHEN EXISTS (SELECT 1 FROM trading_day WHERE market = ? AND trade_date <= ?)
                             AND EXISTS (SELECT 1 FROM trading_day WHERE market = ? AND trade_date >= ?)
                            THEN 1 ELSE 0 END""",
            Integer.class,
            market.name(),
            Date.valueOf(date),
            market.name(),
            Date.valueOf(date));
    return n != null && n == 1;
  }

  public Optional<LocalDate> latest(Market market) {
    Date d =
        jdbc.query(
            "SELECT max(trade_date) FROM trading_day WHERE market = ?",
            rs -> rs.next() ? rs.getDate(1) : null,
            market.name());
    return Optional.ofNullable(d).map(Date::toLocalDate);
  }
}
