package org.jdkxx.trader.storage.marketdata;

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
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Daily bars ({@code daily_bar}). Rows are always stored <b>unadjusted</b>; price adjustment is
 * applied at read time from {@code rehab_factor}.
 *
 * <p>Besides plain reads and writes, this class holds the audit queries of the daily check
 * (continuity, calendar gaps, phantom bars, unsettled bars). Unit tests replace repositories with
 * fakes, so none of this SQL is exercised there; changes must be tested against the dev database.
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class DailyBarRepository {

  /** Coverage statistics over the whole table. */
  public record Coverage(long rows, long instruments, LocalDate earliest, LocalDate latest) {}

  public record InstrumentCoverage(
      long instrumentId, long rows, LocalDate earliest, LocalDate latest) {}

  private final JdbcTemplate jdbc;

  public DailyBarRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Idempotent write (overwrites on conflict), returns the number of rows sent. {@code fetched_at}
   * is reset on every write because {@link #latestSettledDates} uses it to decide whether a bar was
   * written after the close.
   */
  public int upsertAll(long instrumentId, List<DailyBar> bars, String source) {
    if (bars.isEmpty()) {
      return 0;
    }
    List<Object[]> args = new ArrayList<>(bars.size());
    for (DailyBar b : bars) {
      args.add(
          new Object[] {
            instrumentId,
            Date.valueOf(b.tradeDate()),
            b.open(),
            b.high(),
            b.low(),
            b.close(),
            b.lastClose(),
            b.volume(),
            b.turnover(),
            b.turnoverRate(),
            b.changeRate(),
            b.pe(),
            b.blank(),
            source
          });
    }
    jdbc.batchUpdate(
        """
                INSERT INTO daily_bar (instrument_id, trade_date, open, high, low, close, last_close, volume, turnover,
                                       turnover_rate, change_rate, pe, blank, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (instrument_id, trade_date) DO UPDATE SET
                    open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low, close = EXCLUDED.close,
                    last_close = EXCLUDED.last_close, volume = EXCLUDED.volume, turnover = EXCLUDED.turnover,
                    turnover_rate = EXCLUDED.turnover_rate, change_rate = EXCLUDED.change_rate, pe = EXCLUDED.pe,
                    blank = EXCLUDED.blank, source = EXCLUDED.source, fetched_at = now()""",
        args);
    return bars.size();
  }

  public List<DailyBar> find(
      Instrument instrument, long instrumentId, LocalDate from, LocalDate to) {
    return jdbc.query(
        """
                SELECT trade_date, open, high, low, close, last_close, volume, turnover, turnover_rate, change_rate, pe, blank
                FROM daily_bar WHERE instrument_id = ? AND trade_date BETWEEN ? AND ? ORDER BY trade_date""",
        (rs, i) ->
            new DailyBar(
                instrument,
                rs.getDate("trade_date").toLocalDate(),
                rs.getBigDecimal("open"),
                rs.getBigDecimal("high"),
                rs.getBigDecimal("low"),
                rs.getBigDecimal("close"),
                rs.getBigDecimal("last_close"),
                rs.getLong("volume"),
                rs.getBigDecimal("turnover"),
                rs.getBigDecimal("turnover_rate"),
                rs.getBigDecimal("change_rate"),
                rs.getBigDecimal("pe"),
                rs.getBoolean("blank")),
        instrumentId,
        Date.valueOf(from),
        Date.valueOf(to));
  }

  /**
   * Loads several instruments at once (grouped by instrument, dates ascending); instruments without
   * bars are absent from the result.
   */
  public Map<Long, List<DailyBar>> findMany(
      Map<Long, Instrument> instruments, LocalDate from, LocalDate to) {
    Map<Long, List<DailyBar>> out = new HashMap<>();
    if (instruments.isEmpty()) {
      return out;
    }
    jdbc.query(
        """
                SELECT instrument_id, trade_date, open, high, low, close, last_close, volume, turnover, turnover_rate, change_rate, pe, blank
                FROM daily_bar WHERE instrument_id = ANY (?) AND trade_date BETWEEN ? AND ? ORDER BY instrument_id, trade_date""",
        rs -> {
          long id = rs.getLong("instrument_id");
          out.computeIfAbsent(id, k -> new java.util.ArrayList<>())
              .add(
                  new DailyBar(
                      instruments.get(id),
                      rs.getDate("trade_date").toLocalDate(),
                      rs.getBigDecimal("open"),
                      rs.getBigDecimal("high"),
                      rs.getBigDecimal("low"),
                      rs.getBigDecimal("close"),
                      rs.getBigDecimal("last_close"),
                      rs.getLong("volume"),
                      rs.getBigDecimal("turnover"),
                      rs.getBigDecimal("turnover_rate"),
                      rs.getBigDecimal("change_rate"),
                      rs.getBigDecimal("pe"),
                      rs.getBoolean("blank")));
        },
        instruments.keySet().toArray(Long[]::new),
        Date.valueOf(from),
        Date.valueOf(to));
    return out;
  }

  public Optional<LocalDate> latestDate(long instrumentId) {
    Date d =
        jdbc.query(
            "SELECT max(trade_date) FROM daily_bar WHERE instrument_id = ?",
            rs -> rs.next() ? rs.getDate(1) : null,
            instrumentId);
    return Optional.ofNullable(d).map(Date::toLocalDate);
  }

  /**
   * Latest <b>settled</b> trading day per instrument. Settled = the bar had already closed when it
   * was written: its trade date is earlier than the write date (in {@code zone}), or the write
   * happened at or after {@code settledAt} on that day.
   *
   * <p>A same-day bar written before it settled does not count: it holds intraday prices and must
   * not be taken as "the latest bar is already here" and skipped by the re-fetch (before 2.0.2 an
   * intraday deep backfill left such rows, and the evening increment saw the latest date already
   * present and never refreshed them).
   *
   * <p>{@code fetched_at} is a timestamptz; {@code AT TIME ZONE zone} turns it into wall-clock time
   * in the market's zone, so the date and time-of-day comparisons are in US/Eastern terms
   * regardless of the server or JVM time zone. The filter is row-level, so an instrument whose
   * newest bar is unsettled reports its previous settled date.
   */
  public Map<Long, LocalDate> latestSettledDates(ZoneId zone, LocalTime settledAt) {
    Map<Long, LocalDate> m = new HashMap<>();
    jdbc.query(
        """
                SELECT instrument_id, max(trade_date) AS d FROM daily_bar
                WHERE trade_date < (fetched_at AT TIME ZONE CAST(? AS text))::date
                   OR (fetched_at AT TIME ZONE CAST(? AS text))::time >= CAST(? AS time)
                GROUP BY instrument_id""",
        rs -> {
          m.put(rs.getLong("instrument_id"), rs.getDate("d").toLocalDate());
        },
        zone.getId(),
        zone.getId(),
        settledAt.toString());
    return m;
  }

  public record UnsettledBar(long instrumentId, LocalDate tradeDate, Instant fetchedAt) {}

  /**
   * Bars for the write day (or a later date) that were written before they settled; the condition
   * is the exact logical complement of {@link #latestSettledDates}. The limit is clamped to
   * 1..1000.
   */
  public List<UnsettledBar> unsettledBars(ZoneId zone, LocalTime settledAt, int limit) {
    return jdbc.query(
        """
                SELECT instrument_id, trade_date, fetched_at FROM daily_bar
                WHERE trade_date >= (fetched_at AT TIME ZONE CAST(? AS text))::date
                  AND (fetched_at AT TIME ZONE CAST(? AS text))::time < CAST(? AS time)
                ORDER BY trade_date DESC, instrument_id
                LIMIT ?""",
        (rs, i) ->
            new UnsettledBar(
                rs.getLong(1), rs.getDate(2).toLocalDate(), rs.getTimestamp(3).toInstant()),
        zone.getId(),
        zone.getId(),
        settledAt.toString(),
        Math.max(1, Math.min(limit, 1000)));
  }

  /**
   * <b>Total</b> number of {@link #unsettledBars}, uncapped, with the same condition; the list is
   * only a sample. Before 3.5.2 the audit reported the length of the list capped at 1000 as the
   * count (found in the 2026-10-31 review, same trap as {@link #phantomBarCount()}).
   */
  public int unsettledBarCount(ZoneId zone, LocalTime settledAt) {
    Integer n =
        jdbc.queryForObject(
            """
                SELECT count(*) FROM daily_bar
                WHERE trade_date >= (fetched_at AT TIME ZONE CAST(? AS text))::date
                  AND (fetched_at AT TIME ZONE CAST(? AS text))::time < CAST(? AS time)""",
            Integer.class,
            zone.getId(),
            zone.getId(),
            settledAt.toString());
    return n == null ? 0 : n;
  }

  /** Close of the most recent bar (unadjusted). */
  public Optional<java.math.BigDecimal> latestClose(long instrumentId) {
    return jdbc
        .query(
            "SELECT close FROM daily_bar WHERE instrument_id = ? ORDER BY trade_date DESC LIMIT 1",
            (rs, n) -> rs.getBigDecimal("close"),
            instrumentId)
        .stream()
        .findFirst();
  }

  /**
   * Median of the daily-bar P/E, counting <b>positive values only</b>. The broker reports 0 during
   * loss-making periods and for funds (measured 2026-09-19: INTC daily bar currently 0 while the
   * valuation snapshot says -49.99; SPY daily bars are all 0). Here 0 means "no data", not a real
   * value, and mixing it into percentile statistics would drag the median down.
   */
  public Optional<Double> peMedian(long instrumentId, LocalDate from) {
    // HAVING pushes "not a single positive P/E" down to the database: in that case it returns <b>no
    // row</b> (an aggregate without GROUP BY otherwise always returns one row, with a NULL median).
    // 3.1.0 used a RowMapper returning null to mean "none", and findFirst() then threw an NPE on
    // Optional.of(null); in production SPY (daily-bar pe all 0) returned 500 because of it. Unit
    // tests use a fake repository, so this path is only reachable with real data.
    return jdbc
        .query(
            """
                SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY pe) AS median
                FROM daily_bar WHERE instrument_id = ? AND trade_date >= ? AND pe > 0
                HAVING count(*) > 0
                """,
            (rs, n) -> rs.getDouble("median"),
            instrumentId,
            java.sql.Date.valueOf(from))
        .stream()
        .findFirst();
  }

  public Coverage coverage() {
    return jdbc.queryForObject(
        "SELECT count(*) AS rows, count(DISTINCT instrument_id) AS instruments, min(trade_date) AS mn, max(trade_date) AS mx FROM daily_bar",
        (rs, i) ->
            new Coverage(
                rs.getLong("rows"),
                rs.getLong("instruments"),
                rs.getDate("mn") == null ? null : rs.getDate("mn").toLocalDate(),
                rs.getDate("mx") == null ? null : rs.getDate("mx").toLocalDate()));
  }

  /**
   * Unadjusted closes of these instruments on the given trading day; instruments without a bar that
   * day are absent.
   */
  public Map<Long, java.math.BigDecimal> closesOn(LocalDate date, Collection<Long> instrumentIds) {
    Map<Long, java.math.BigDecimal> m = new HashMap<>();
    if (instrumentIds.isEmpty()) {
      return m;
    }
    jdbc.query(
        "SELECT instrument_id, close FROM daily_bar WHERE trade_date = ? AND instrument_id = ANY (?)",
        rs -> {
          m.put(rs.getLong(1), rs.getBigDecimal(2));
        },
        Date.valueOf(date),
        (Object) instrumentIds.toArray(Long[]::new));
    return m;
  }

  // ------------------------------------------------------------------ audit queries

  /** Ids of the instruments that have a bar on the given trading day. */
  public java.util.Set<Long> instrumentIdsWithBarOn(LocalDate date) {
    java.util.Set<Long> ids = new java.util.HashSet<>();
    jdbc.query(
        "SELECT instrument_id FROM daily_bar WHERE trade_date = ?",
        rs -> {
          ids.add(rs.getLong(1));
        },
        Date.valueOf(date));
    return ids;
  }

  public record ContinuityIssue(
      long instrumentId,
      LocalDate tradeDate,
      java.math.BigDecimal lastClose,
      java.math.BigDecimal prevClose) {}

  /**
   * Rows in the range whose last_close differs from the previous bar's close (a difference means a
   * trading day is missing in between, or the broker's data is wrong). Differences up to 0.0005 are
   * treated as rounding.
   *
   * <p>The inner query moves the lower bound back 7 days only to give {@code lag()} a preceding
   * value for the first bar in the window; the outer query <b>must filter by from again</b>,
   * otherwise problems inside those 7 days, which the caller did not ask for, would be reported as
   * if they were in the window (found in the 2026-09-25 full-project review).
   *
   * <p>Known limit (see the pitfall table): this check cannot see gaps whose last_close is
   * self-consistent with the gap; {@link #gaps} compares against the trading calendar for that.
   */
  public List<ContinuityIssue> continuityIssues(LocalDate from, LocalDate to, int limit) {
    return jdbc.query(
        """
                SELECT instrument_id, trade_date, last_close, prev FROM (
                    SELECT instrument_id, trade_date, last_close,
                           lag(close) OVER (PARTITION BY instrument_id ORDER BY trade_date) AS prev
                    FROM daily_bar WHERE trade_date BETWEEN ? AND ?
                ) x WHERE prev IS NOT NULL AND last_close IS NOT NULL AND abs(last_close - prev) > 0.0005
                  AND trade_date >= ?
                ORDER BY trade_date DESC, instrument_id LIMIT ?""",
        (rs, i) ->
            new ContinuityIssue(
                rs.getLong(1),
                rs.getDate(2).toLocalDate(),
                rs.getBigDecimal(3),
                rs.getBigDecimal(4)),
        Date.valueOf(from.minusDays(7)),
        Date.valueOf(to),
        Date.valueOf(from),
        Math.max(1, limit));
  }

  /**
   * <b>Total</b> number of {@link #continuityIssues}, uncapped, with the same condition word for
   * word; the list is only a sample. Before 3.5.2 the audit reported the length of the list capped
   * at 50 as the count (found in the 2026-10-31 review, same trap as {@link #phantomBarCount()}).
   */
  public int continuityIssueCount(LocalDate from, LocalDate to) {
    Integer n =
        jdbc.queryForObject(
            """
                SELECT count(*) FROM (
                    SELECT trade_date, last_close,
                           lag(close) OVER (PARTITION BY instrument_id ORDER BY trade_date) AS prev
                    FROM daily_bar WHERE trade_date BETWEEN ? AND ?
                ) x WHERE prev IS NOT NULL AND last_close IS NOT NULL AND abs(last_close - prev) > 0.0005
                  AND trade_date >= ?""",
            Integer.class,
            Date.valueOf(from.minusDays(7)),
            Date.valueOf(to),
            Date.valueOf(from));
    return n == null ? 0 : n;
  }

  public record DaySanity(
      long bars,
      long ohlcInconsistent,
      long nonPositiveClose,
      long blank,
      long zeroVolume,
      long nullTurnover) {}

  /**
   * Plausibility counts for one day: OHLC ordering, non-positive close, blank bars, zero volume,
   * missing turnover.
   */
  public DaySanity sanityOn(LocalDate date) {
    return jdbc.queryForObject(
        """
                SELECT count(*) AS bars,
                       count(*) FILTER (WHERE high < low OR close > high OR close < low OR open > high OR open < low) AS ohlc,
                       count(*) FILTER (WHERE close <= 0) AS nonpos,
                       count(*) FILTER (WHERE blank) AS blank,
                       count(*) FILTER (WHERE volume = 0) AS zerovol,
                       count(*) FILTER (WHERE turnover IS NULL) AS nullturn
                FROM daily_bar WHERE trade_date = ?""",
        (rs, i) ->
            new DaySanity(
                rs.getLong("bars"),
                rs.getLong("ohlc"),
                rs.getLong("nonpos"),
                rs.getLong("blank"),
                rs.getLong("zerovol"),
                rs.getLong("nullturn")),
        Date.valueOf(date));
  }

  public Optional<LocalDate> maxTradeDate() {
    Date d =
        jdbc.query("SELECT max(trade_date) FROM daily_bar", rs -> rs.next() ? rs.getDate(1) : null);
    return Optional.ofNullable(d).map(Date::toLocalDate);
  }

  /**
   * Trading days in [from, to) on which <b>at least minInstruments</b> of the given instruments
   * have a bar (distinct, ascending). Used to reconstruct the early trading calendar the broker
   * cannot provide: pass large caps (pool / holdings / benchmark), which trade every trading day.
   *
   * <p>The threshold is essential: Futu was measured to return dirty SPY bars on three US market
   * holidays (2011-07-04 Independence Day, 2012-04-06 Good Friday, 2012-05-28 Memorial Day; zero
   * turnover, clearly wrong lows). Taking the plain union of "has a bar" would turn those holidays
   * into trading days. Real trading days have 13+ instruments trading together while the dirty data
   * has only 1, so the gap is wide; a threshold of 2 is enough and far from both sides. The
   * threshold is capped at the number of instruments passed in.
   *
   * <p>The id list is inlined into the SQL; that is safe because the values are {@code Long}s, not
   * user text.
   */
  public List<LocalDate> distinctTradeDates(
      Collection<Long> instrumentIds, LocalDate from, LocalDate to, int minInstruments) {
    if (instrumentIds.isEmpty()) {
      return List.of();
    }
    String ids =
        instrumentIds.stream()
            .map(String::valueOf)
            .collect(java.util.stream.Collectors.joining(","));
    int min = Math.max(1, Math.min(minInstruments, instrumentIds.size()));
    return jdbc.query(
        "SELECT trade_date FROM daily_bar WHERE instrument_id IN ("
            + ids
            + ") AND trade_date >= ? AND trade_date < ? GROUP BY trade_date HAVING count(*) >= ? ORDER BY trade_date",
        (rs, i) -> rs.getDate(1).toLocalDate(),
        Date.valueOf(from),
        Date.valueOf(to),
        min);
  }

  /**
   * Finds gaps against the trading calendar: trading days that are in the calendar but missing from
   * the table, within the span where the instrument itself has data.
   *
   * <p>These gaps are <b>invisible to the previous-close continuity check</b>: Futu's SPY was
   * measured to be missing 26 trading days (2009~2012), and the last_close it reports is consistent
   * with the gaps, so comparing previous closes shows nothing. Only a comparison with the calendar
   * finds them.
   *
   * @param from start date (inclusive); only the part that overlaps the instrument's own data span
   *     is counted
   * @param to end date (inclusive)
   */
  public List<InstrumentGap> gaps(LocalDate from, LocalDate to, int limit) {
    return gaps(from, to, limit, null);
  }

  /**
   * Same as {@link #gaps(LocalDate, LocalDate, int)}, optionally restricted to some instruments.
   * Instruments are ordered by number of missing days, largest first.
   *
   * @param onlyInstruments only these instruments; null means no restriction. <b>The filter must be
   *     pushed down into SQL</b>: if the caller takes {@code LIMIT n} first and then filters by
   *     instrument in memory, that is "truncate, then filter"; when the first n rows happen to
   *     contain none of the instruments of interest the result is empty and the daily check reports
   *     "no gaps" (found in the 2026-09-25 full-project review). The only string formatting here
   *     inserts a fixed filter clause; values are bound.
   */
  public List<InstrumentGap> gaps(
      LocalDate from, LocalDate to, int limit, java.util.Collection<Long> onlyInstruments) {
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
    return jdbc.query(
        """
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
                LIMIT ?"""
            .formatted(filter),
        (rs, i) ->
            new InstrumentGap(
                rs.getLong("instrument_id"),
                rs.getLong("missing"),
                rs.getDate("first_missing").toLocalDate(),
                rs.getDate("last_missing").toLocalDate()),
        args.toArray());
  }

  /** Trading days one instrument is missing compared with the calendar. */
  public record InstrumentGap(
      long instrumentId, long missing, LocalDate firstMissing, LocalDate lastMissing) {}

  /** Totals over all gaps, not just the listed ones: instruments with a gap, and missing days. */
  public record GapTotals(long instruments, long missingDays) {}

  /**
   * Totals of {@link #gaps(LocalDate, LocalDate, int, java.util.Collection)} without the limit:
   * same span, join and filter; the list stays a sample. Before 3.5.2 the audit reported the length
   * of the list capped at 20 instruments as the instrument count and summed missing days over those
   * 20 only (found in the 2026-10-31 review, same trap as {@link #phantomBarCount()}).
   *
   * @param onlyInstruments only these instruments; null means no restriction (pushed down into SQL
   *     for the same reason as in {@code gaps})
   */
  public GapTotals gapTotals(
      LocalDate from, LocalDate to, java.util.Collection<Long> onlyInstruments) {
    if (onlyInstruments != null && onlyInstruments.isEmpty()) {
      return new GapTotals(0, 0);
    }
    List<Object> args = new ArrayList<>();
    String filter = "";
    if (onlyInstruments != null) {
      filter = " WHERE instrument_id = ANY (?)";
      args.add(onlyInstruments.toArray(Long[]::new));
    }
    args.add(Date.valueOf(from));
    args.add(Date.valueOf(to));
    // Summing the per-instrument counts of gaps() equals count(*) over the ungrouped join, and the
    // number of groups equals count(DISTINCT instrument_id); checked on db_trader_dev 2026-10-31.
    return jdbc.queryForObject(
        """
                WITH span AS (
                    SELECT instrument_id, min(trade_date) AS first_bar, max(trade_date) AS last_bar
                    FROM daily_bar%s GROUP BY instrument_id)
                SELECT count(DISTINCT s.instrument_id) AS instruments, count(*) AS missing
                FROM span s
                JOIN trading_day t ON t.market = 'US'
                     AND t.trade_date BETWEEN greatest(s.first_bar, ?) AND least(s.last_bar, ?)
                WHERE NOT EXISTS (
                    SELECT 1 FROM daily_bar b
                    WHERE b.instrument_id = s.instrument_id AND b.trade_date = t.trade_date)"""
            .formatted(filter),
        (rs, i) -> new GapTotals(rs.getLong("instruments"), rs.getLong("missing")),
        args.toArray());
  }

  /**
   * Phantom bars: rows that fall inside the calendar's covered span but are not in the trading
   * calendar.
   *
   * <p>Futu was measured to leave dirty SPY bars on three US market holidays (2011-07-04,
   * 2012-04-06, 2012-05-28, all with zero turnover; the Independence Day bar even fabricated a 15%
   * intraday crash). The calendar was verified independently to be fully correct within that span,
   * so "not in the calendar" is equivalent to "should not exist". Outside the span (years the
   * calendar does not cover) nothing is touched. With an empty calendar the span is NULL and
   * nothing matches.
   *
   * <p>This is a <b>capped sample</b> (limit clamped to 1..1000), not a count; use {@link
   * #phantomBarCount()} for the total.
   */
  public List<PhantomBar> phantomBars(int limit) {
    return jdbc.query(
        """
                WITH span AS (SELECT min(trade_date) AS lo, max(trade_date) AS hi FROM trading_day WHERE market = 'US')
                SELECT b.instrument_id, b.trade_date, b.open, b.high, b.low, b.close, b.volume, b.turnover
                FROM daily_bar b, span s
                WHERE b.trade_date BETWEEN s.lo AND s.hi
                  AND NOT EXISTS (SELECT 1 FROM trading_day t WHERE t.market = 'US' AND t.trade_date = b.trade_date)
                ORDER BY b.trade_date
                LIMIT ?""",
        (rs, i) ->
            new PhantomBar(
                rs.getLong("instrument_id"),
                rs.getDate("trade_date").toLocalDate(),
                rs.getBigDecimal("open"),
                rs.getBigDecimal("high"),
                rs.getBigDecimal("low"),
                rs.getBigDecimal("close"),
                rs.getLong("volume"),
                rs.getBigDecimal("turnover")),
        Math.max(1, Math.min(limit, 1000)));
  }

  /**
   * Cap per correction run: the dry run lists this many and the correction deletes exactly those.
   * It lives here because both the audit and the correction refer to it and both already depend on
   * this class; making the audit depend on {@code MarketDataFacade} would invert the dependency.
   */
  public static final int PHANTOM_BATCH = 200;

  /**
   * <b>Total</b> number of phantom bars, uncapped. {@link #phantomBars} is a capped list; <b>do not
   * use its size as the total</b>. Before 3.1.1 the audit used {@code phantomBars(20).size()} as
   * the count, the correction dry run used {@code phantomBars(200).size()}, and the delete removed
   * everything matching the condition: three different measures, so you could see 20 rows and have
   * thousands deleted (found in the 2026-09-25 full-project review).
   */
  public int phantomBarCount() {
    Integer n =
        jdbc.queryForObject(
            """
                WITH span AS (SELECT min(trade_date) AS lo, max(trade_date) AS hi FROM trading_day WHERE market = 'US')
                SELECT count(*) FROM daily_bar b, span s
                WHERE b.trade_date BETWEEN s.lo AND s.hi
                  AND NOT EXISTS (SELECT 1 FROM trading_day t WHERE t.market = 'US' AND t.trade_date = b.trade_date)""",
            Integer.class);
    return n == null ? 0 : n;
  }

  /**
   * Deletes <b>only the rows passed in</b> and returns the number deleted; an empty list returns 0
   * without issuing any SQL.
   *
   * <p>There is deliberately no "delete everything matching" variant: what the dry run showed is
   * what gets deleted, and anything beyond that waits for the next round. Deleted bars can only be
   * restored by a backfill, and the backfill pulls the broker's dirty bars back in (see the pitfall
   * table), so several rounds are preferable. If the calendar itself breaks, the whole table would
   * be judged phantom; "delete only what was shown" is then the last safety gate.
   *
   * <p>The phantom condition is still applied: the list came from an earlier step, the calendar may
   * have been backfilled in between, and such rows are then no longer phantoms and must not be
   * deleted.
   *
   * <p>The targets are matched through an inline {@code VALUES} list of bound {@code (bigint,
   * date)} pairs; only placeholders are formatted into the SQL. Callers pass at most {@link
   * #PHANTOM_BATCH} rows, well within the driver's bind-parameter limit.
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
    return jdbc.update(
        """
                WITH span AS (SELECT min(trade_date) AS lo, max(trade_date) AS hi FROM trading_day WHERE market = 'US')
                DELETE FROM daily_bar b
                USING span s
                WHERE b.trade_date BETWEEN s.lo AND s.hi
                  AND NOT EXISTS (SELECT 1 FROM trading_day t WHERE t.market = 'US' AND t.trade_date = b.trade_date)
                  AND EXISTS (SELECT 1 FROM (VALUES %s) AS v(id, d)
                              WHERE v.id = b.instrument_id AND v.d = b.trade_date)"""
            .formatted(values),
        args.toArray());
  }

  public record PhantomBar(
      long instrumentId,
      LocalDate tradeDate,
      java.math.BigDecimal open,
      java.math.BigDecimal high,
      java.math.BigDecimal low,
      java.math.BigDecimal close,
      long volume,
      java.math.BigDecimal turnover) {}

  /**
   * Actual coverage of one instrument (row count and date span); empty when it has no bars at all.
   *
   * <p>The aggregate always returns one row, so the mapper returns null for "no bars" and the
   * stream drops it with {@code filter(nonNull)} <i>before</i> {@code findFirst()}. That order is
   * what keeps this safe; the 3.1.0 {@code peMedian} NPE came from calling {@code findFirst()} on a
   * null element.
   */
  public Optional<InstrumentCoverage> coverage(long instrumentId) {
    return jdbc
        .query(
            "SELECT count(*) AS rows, min(trade_date) AS mn, max(trade_date) AS mx FROM daily_bar WHERE instrument_id = ?",
            (rs, i) ->
                rs.getLong("rows") == 0
                    ? null
                    : new InstrumentCoverage(
                        instrumentId,
                        rs.getLong("rows"),
                        rs.getDate("mn").toLocalDate(),
                        rs.getDate("mx").toLocalDate()),
            instrumentId)
        .stream()
        .filter(java.util.Objects::nonNull)
        .findFirst();
  }

  private static final org.springframework.jdbc.core.RowMapper<InstrumentCoverage> COVERAGE =
      (rs, i) ->
          new InstrumentCoverage(
              rs.getLong("instrument_id"),
              rs.getLong("rows"),
              rs.getDate("mn").toLocalDate(),
              rs.getDate("mx").toLocalDate());

  public List<InstrumentCoverage> coverageByInstrument() {
    return jdbc.query(
        "SELECT instrument_id, count(*) AS rows, min(trade_date) AS mn, max(trade_date) AS mx FROM daily_bar GROUP BY instrument_id",
        COVERAGE);
  }

  /**
   * Coverage for the given instruments only (used for the dozen-odd pool members, avoiding a
   * full-table scan); instruments without bars return no row.
   */
  public List<InstrumentCoverage> coverageByInstrument(Collection<Long> instrumentIds) {
    if (instrumentIds.isEmpty()) {
      return List.of();
    }
    return jdbc.query(
        "SELECT instrument_id, count(*) AS rows, min(trade_date) AS mn, max(trade_date) AS mx FROM daily_bar "
            + "WHERE instrument_id = ANY (?) GROUP BY instrument_id",
        COVERAGE,
        (Object) instrumentIds.toArray(Long[]::new));
  }
}
