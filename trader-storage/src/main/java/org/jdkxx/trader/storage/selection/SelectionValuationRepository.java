package org.jdkxx.trader.storage.selection;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jdkxx.trader.domain.selection.ValuationMetric;
import org.jdkxx.trader.domain.selection.ValuationPoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Daily valuation for quant stock selection (§25.6). The three metrics come from three separate
 * requests and are written column by column. Column names are only ever produced by {@code
 * column(ValuationMetric)}, never taken from input.
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionValuationRepository {

  public record Coverage(
      long rows,
      long instruments,
      LocalDate earliest,
      LocalDate latest,
      long pe,
      long pb,
      long ps) {}

  /**
   * Same-day comparison with the valuation snapshot: the historical P/E series was measured to
   * match the snapshot's pe_ttm digit for digit; P/B is still to be verified (§25.6).
   */
  public record Agreement(
      long compared, long peMismatch, long pbMismatch, List<Mismatch> samples) {}

  public record Mismatch(
      String symbol,
      LocalDate tradeDate,
      BigDecimal histPe,
      BigDecimal snapPe,
      BigDecimal histPb,
      BigDecimal snapPb) {}

  /**
   * Both sides keep only 3~4 decimals; differences within this count as equal. A constant, so
   * inlining it into SQL is safe.
   */
  static final String TOLERANCE = "0.0015";

  private final JdbcTemplate jdbc;

  public SelectionValuationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Writes Futu's valuation history for one metric and returns the row count. Existing rows only
   * have this column overwritten (and become source HIST).
   */
  public int upsertHistory(long instrumentId, ValuationMetric metric, List<ValuationPoint> points) {
    String column = column(metric);
    List<Object[]> args = new ArrayList<>(points.size());
    for (ValuationPoint p : points) {
      args.add(new Object[] {instrumentId, Date.valueOf(p.tradeDate()), p.value()});
    }
    if (args.isEmpty()) {
      return 0;
    }
    jdbc.batchUpdate(
        "INSERT INTO selection_valuation (instrument_id, trade_date, "
            + column
            + ", source) VALUES (?, ?, ?, 'HIST') "
            + "ON CONFLICT (instrument_id, trade_date) DO UPDATE SET "
            + column
            + " = EXCLUDED."
            + column
            + ", source = 'HIST', fetched_at = now()",
        args);
    return args.size();
  }

  /**
   * Extends the valuation series with the day's valuation snapshot (daily job, §25.6). P/E and P/B
   * are taken directly from the snapshot (same definition as the historical series). The snapshot
   * has no P/S, so it is carried forward as previous P/S × today's P/E ÷ previous P/E: between two
   * reports the denominators of P/E and P/S do not change, so the ratio is the price ratio, and it
   * is unaffected by splits. If P/E changed sign or is zero, the P/B ratio is used instead; if
   * neither works, P/S is left empty (and stays empty on later days until a HIST row provides a new
   * base). A day that already has a historical-series (HIST) row is not overwritten. Returns the
   * number of rows written.
   *
   * <p>"Previous row" is the latest earlier row of any source, found with a {@code LATERAL}
   * subquery per snapshot.
   */
  public int appendFromSnapshot(LocalDate date, java.util.Collection<Long> instrumentIds) {
    if (instrumentIds.isEmpty()) {
      return 0;
    }
    return jdbc.update(
        """
                INSERT INTO selection_valuation (instrument_id, trade_date, pe_ttm, pb, ps, source)
                SELECT s.instrument_id, s.trade_date, s.pe_ttm, s.pb,
                       CASE WHEN p.ps IS NULL THEN NULL
                            WHEN p.pe_ttm IS NOT NULL AND p.pe_ttm <> 0 AND s.pe_ttm IS NOT NULL AND sign(s.pe_ttm) = sign(p.pe_ttm)
                                THEN round(p.ps * s.pe_ttm / p.pe_ttm, 4)
                            WHEN p.pb IS NOT NULL AND p.pb <> 0 AND s.pb IS NOT NULL AND sign(s.pb) = sign(p.pb)
                                THEN round(p.ps * s.pb / p.pb, 4)
                       END,
                       'SNAPSHOT'
                FROM valuation_snapshot s
                LEFT JOIN LATERAL (SELECT v.pe_ttm, v.pb, v.ps FROM selection_valuation v
                                   WHERE v.instrument_id = s.instrument_id AND v.trade_date < s.trade_date
                                   ORDER BY v.trade_date DESC LIMIT 1) p ON true
                WHERE s.trade_date = ? AND s.instrument_id = ANY (?)
                ON CONFLICT (instrument_id, trade_date) DO NOTHING""",
        Date.valueOf(date),
        (Object) instrumentIds.toArray(Long[]::new));
  }

  /**
   * Number of instruments scored on a day (by that model version) that also have a valuation on
   * that day (for the audit). Counting only the scored set keeps numerator and denominator the same
   * set: valuations of names outside it (ETFs, names that left the universe) do not count.
   */
  public long countScoredOn(String modelVersion, LocalDate date) {
    Long n =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM selection_valuation v
            WHERE v.trade_date = ?
              AND EXISTS (SELECT 1 FROM selection_score s
                          WHERE s.model_version = ? AND s.trade_date = v.trade_date
                            AND s.instrument_id = v.instrument_id)""",
            Long.class,
            Date.valueOf(date),
            modelVersion);
    return n == null ? 0 : n;
  }

  public Coverage coverage() {
    return jdbc.queryForObject(
        """
                SELECT count(*) AS n, count(DISTINCT instrument_id) AS k, min(trade_date) AS lo, max(trade_date) AS hi,
                       count(pe_ttm) AS pe, count(pb) AS pb, count(ps) AS ps
                FROM selection_valuation""",
        (rs, n) ->
            new Coverage(
                rs.getLong("n"),
                rs.getLong("k"),
                rs.getDate("lo") == null ? null : rs.getDate("lo").toLocalDate(),
                rs.getDate("hi") == null ? null : rs.getDate("hi").toLocalDate(),
                rs.getLong("pe"),
                rs.getLong("pb"),
                rs.getLong("ps")));
  }

  /**
   * Compares HIST rows with the snapshot of the same day. The {@code join} text block has no
   * trailing newline, so the fragments appended to it begin with a space; the sample query splices
   * the instrument join in front of its WHERE.
   */
  public Agreement snapshotAgreement(int sampleLimit) {
    String join =
        """
                FROM selection_valuation v JOIN valuation_snapshot s ON s.instrument_id = v.instrument_id AND s.trade_date = v.trade_date
                WHERE v.source = 'HIST'""";
    String peBad =
        "v.pe_ttm IS NOT NULL AND s.pe_ttm IS NOT NULL AND abs(v.pe_ttm - s.pe_ttm) > " + TOLERANCE;
    String pbBad = "v.pb IS NOT NULL AND s.pb IS NOT NULL AND abs(v.pb - s.pb) > " + TOLERANCE;
    long[] counts =
        jdbc.queryForObject(
            "SELECT count(*) AS n, count(*) FILTER (WHERE "
                + peBad
                + ") AS pe, "
                + "count(*) FILTER (WHERE "
                + pbBad
                + ") AS pb "
                + join,
            (rs, n) -> new long[] {rs.getLong("n"), rs.getLong("pe"), rs.getLong("pb")});
    List<Mismatch> samples =
        jdbc.query(
            "SELECT i.symbol, v.trade_date, v.pe_ttm AS hpe, s.pe_ttm AS spe, v.pb AS hpb, s.pb AS spb "
                + join.replace("WHERE", "JOIN instrument i ON i.id = v.instrument_id WHERE")
                + " AND (("
                + peBad
                + ") OR ("
                + pbBad
                + ")) ORDER BY v.trade_date DESC, i.symbol LIMIT ?",
            (rs, n) ->
                new Mismatch(
                    rs.getString("symbol"),
                    rs.getDate("trade_date").toLocalDate(),
                    rs.getBigDecimal("hpe"),
                    rs.getBigDecimal("spe"),
                    rs.getBigDecimal("hpb"),
                    rs.getBigDecimal("spb")),
            sampleLimit);
    return new Agreement(counts[0], counts[1], counts[2], samples);
  }

  static String column(ValuationMetric metric) {
    return switch (metric) {
      case PE -> "pe_ttm";
      case PB -> "pb";
      case PS -> "ps";
    };
  }
}
