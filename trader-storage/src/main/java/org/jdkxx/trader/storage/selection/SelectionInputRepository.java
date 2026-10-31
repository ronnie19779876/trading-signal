package org.jdkxx.trader.storage.selection;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jdkxx.trader.domain.selection.SplitAdjustedBar;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bulk read-only loading for quant stock-selection scoring (§25.5). A replay loads everything at
 * once and slices it by day, avoiding a round trip per day and instrument (through the dev
 * instance's tunnel each round trip takes about 330 ms).
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionInputRepository {

  /**
   * Report fields used by scoring (three templates, measured on the dev database 2026-10-12; field
   * ids are per statement and never reused across statements).
   */
  public static final long[] INCOME_FIELDS = {
    8002, 8004, 8043, 8048, 9002, 9046, 9051, 10002, 10047, 10052
  };

  public static final long[] BALANCE_FIELDS = {8085, 9070, 10065};
  public static final long[] CASH_FLOW_FIELDS = {8072, 9078, 10078};

  /**
   * One quarterly line item. periodEnd is the stored period end (one day earlier than the real
   * period end).
   */
  public record FactRow(
      long instrumentId,
      String statement,
      LocalDate periodEnd,
      String periodText,
      long fieldId,
      double value) {}

  public record ValuationRow(
      long instrumentId, LocalDate tradeDate, Double pe, Double pb, Double ps) {}

  private final JdbcTemplate jdbc;

  public SelectionInputRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * The scoring fields from quarterly reports (single quarters only, i.e. labels with /Q). Each
   * statement type is paired with its own field list because field ids mean different things in
   * different statements.
   */
  public List<FactRow> quarterlyFacts(Collection<Long> instrumentIds) {
    List<FactRow> out = new ArrayList<>();
    if (instrumentIds.isEmpty()) {
      return out;
    }
    jdbc.query(
        """
                SELECT r.instrument_id, r.statement, r.period_end, r.period_text, i.field_id, i.value
                FROM financial_report r JOIN financial_item i ON i.report_id = r.id
                WHERE r.instrument_id = ANY (?) AND r.period_text ~ '/Q[1-4]$' AND i.value IS NOT NULL
                  AND ((r.statement = 'INCOME' AND i.field_id = ANY (?)) OR (r.statement = 'BALANCE_SHEET' AND i.field_id = ANY (?))
                       OR (r.statement = 'CASH_FLOW' AND i.field_id = ANY (?)))
                ORDER BY r.instrument_id, r.period_end""",
        rs -> {
          out.add(
              new FactRow(
                  rs.getLong(1),
                  rs.getString(2),
                  rs.getDate(3).toLocalDate(),
                  rs.getString(4),
                  rs.getLong(5),
                  rs.getDouble(6)));
        },
        (Object) instrumentIds.toArray(Long[]::new),
        (Object) box(INCOME_FIELDS),
        (Object) box(BALANCE_FIELDS),
        (Object) box(CASH_FLOW_FIELDS));
    return out;
  }

  /**
   * Instrument → all announcement trading days (regardless of period label, for nearest-date
   * matching).
   */
  public Map<Long, List<LocalDate>> pubDates(Collection<Long> instrumentIds) {
    Map<Long, List<LocalDate>> out = new HashMap<>();
    if (instrumentIds.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT instrument_id, pub_date FROM selection_earnings_event WHERE instrument_id = ANY (?) ORDER BY instrument_id, pub_date",
        rs -> {
          out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
              .add(rs.getDate(2).toLocalDate());
        },
        (Object) instrumentIds.toArray(Long[]::new));
    return out;
  }

  /**
   * Streams valuations row by row to a callback (a full replay is about 1.18 million rows), so this
   * method itself does not build a big list. Note that the PostgreSQL driver still buffers the
   * whole result set in memory unless a fetch size is set inside a transaction, which is not done
   * here.
   */
  public void forEachValuation(
      Collection<Long> instrumentIds,
      LocalDate from,
      LocalDate to,
      Consumer<ValuationRow> consumer) {
    if (instrumentIds.isEmpty()) {
      return;
    }
    jdbc.query(
        """
                SELECT instrument_id, trade_date, pe_ttm, pb, ps FROM selection_valuation
                WHERE instrument_id = ANY (?) AND trade_date BETWEEN ? AND ? ORDER BY instrument_id, trade_date""",
        rs -> {
          consumer.accept(
              new ValuationRow(
                  rs.getLong(1), rs.getDate(2).toLocalDate(), num(rs, 3), num(rs, 4), num(rs, 5)));
        },
        (Object) instrumentIds.toArray(Long[]::new),
        Date.valueOf(from),
        Date.valueOf(to));
  }

  /**
   * IBKR daily bars of instruments that passed the seam check; failed ones are not returned
   * (momentum for that span is recorded as missing, §25.6).
   */
  public Map<Long, List<SplitAdjustedBar>> seamPassedIbkrBars(Collection<Long> instrumentIds) {
    Map<Long, List<SplitAdjustedBar>> out = new HashMap<>();
    if (instrumentIds.isEmpty()) {
      return out;
    }
    jdbc.query(
        """
                SELECT p.instrument_id, p.trade_date, p.close FROM selection_price_bar p
                JOIN selection_price_seam s ON s.instrument_id = p.instrument_id AND s.ok
                WHERE p.instrument_id = ANY (?) ORDER BY p.instrument_id, p.trade_date""",
        rs -> {
          out.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
              .add(
                  new SplitAdjustedBar(
                      rs.getDate(2).toLocalDate(), null, null, null, rs.getBigDecimal(3), 0));
        },
        (Object) instrumentIds.toArray(Long[]::new));
    return out;
  }

  private static Double num(java.sql.ResultSet rs, int col) throws java.sql.SQLException {
    java.math.BigDecimal v = rs.getBigDecimal(col);
    return v == null ? null : v.doubleValue();
  }

  private static Long[] box(long[] a) {
    Long[] b = new Long[a.length];
    for (int i = 0; i < a.length; i++) {
      b[i] = a[i];
    }
    return b;
  }
}
