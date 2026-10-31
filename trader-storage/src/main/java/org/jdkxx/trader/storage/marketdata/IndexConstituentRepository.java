package org.jdkxx.trader.storage.marketdata;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jdkxx.trader.domain.IndexCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Index constituents and their membership history ({@code since} / {@code until}; until NULL =
 * current member). {@code since} is the sync date on which the membership was first seen, not the
 * index's own "date added".
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class IndexConstituentRepository {

  private static final RowMapper<ConstituentRow> MAPPER =
      (rs, i) ->
          new ConstituentRow(
              IndexCode.valueOf(rs.getString("index_code")),
              rs.getLong("instrument_id"),
              rs.getString("sector"),
              rs.getString("sub_industry"),
              rs.getString("classification"),
              rs.getDate("since").toLocalDate(),
              rs.getDate("until") == null ? null : rs.getDate("until").toLocalDate(),
              rs.getString("source"));

  private final JdbcTemplate jdbc;

  public IndexConstituentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<ConstituentRow> current(IndexCode index) {
    return jdbc.query(
        "SELECT * FROM index_constituent WHERE index_code = ? AND until IS NULL",
        MAPPER,
        index.name());
  }

  public List<ConstituentRow> currentAll() {
    return jdbc.query("SELECT * FROM index_constituent WHERE until IS NULL", MAPPER);
  }

  /**
   * Constituents valid on a given day: since &lt;= d and (not exited, or until later than d). The
   * exit day is stored as until and the instrument no longer counts as a member on that day
   * (consistent with how evaluation on the sync day treats it).
   *
   * <p><b>Membership before the first sync was never recorded</b>: the batch stored by the first
   * sync of each index (since = the earliest since <b>of that index</b>) is treated as having
   * always been a member; otherwise auditing earlier dates would give an empty set. An instrument
   * in both indexes appears once per index.
   *
   * <p>The earliest since is taken per index, not across the whole table (found in the 2026-10-31
   * review): with the table-wide minimum, an index first synced later than the other has a first
   * batch whose since is not that minimum, so every date between the two first syncs gets an empty
   * set for that index.
   */
  public List<ConstituentRow> validOn(LocalDate d) {
    return jdbc.query(
        """
                SELECT * FROM index_constituent c
                WHERE (since <= ?
                       OR since = (SELECT min(m.since) FROM index_constituent m WHERE m.index_code = c.index_code))
                  AND (until IS NULL OR until > ?)""",
        MAPPER,
        Date.valueOf(d),
        Date.valueOf(d));
  }

  public List<ConstituentRow> historyOf(long instrumentId) {
    return jdbc.query(
        "SELECT * FROM index_constituent WHERE instrument_id = ? ORDER BY since",
        MAPPER,
        instrumentId);
  }

  /**
   * Adds a membership starting at {@code since}; re-adding the same (index, instrument, since)
   * reopens it (until = NULL).
   */
  public void add(
      IndexCode index,
      long instrumentId,
      String sector,
      String subIndustry,
      LocalDate since,
      String source) {
    jdbc.update(
        """
                INSERT INTO index_constituent (index_code, instrument_id, sector, sub_industry, classification, since, source)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (index_code, instrument_id, since) DO UPDATE SET sector = EXCLUDED.sector,
                       sub_industry = EXCLUDED.sub_industry, until = NULL, source = EXCLUDED.source""",
        index.name(),
        instrumentId,
        sector,
        subIndustry,
        index.classification(),
        Date.valueOf(since),
        source);
  }

  public void updateSector(IndexCode index, long instrumentId, String sector, String subIndustry) {
    jdbc.update(
        "UPDATE index_constituent SET sector = ?, sub_industry = ? WHERE index_code = ? AND instrument_id = ? AND until IS NULL",
        sector,
        subIndustry,
        index.name(),
        instrumentId);
  }

  /**
   * Records the index's own "date added" on the current membership (V20, §30.5). A null date does
   * nothing, so a source that stops giving dates never wipes one already stored.
   */
  public void setAddedOn(IndexCode index, long instrumentId, LocalDate addedOn) {
    if (addedOn == null) {
      return;
    }
    jdbc.update(
        "UPDATE index_constituent SET added_on = ? WHERE index_code = ? AND instrument_id = ? AND until IS NULL",
        Date.valueOf(addedOn),
        index.name(),
        instrumentId);
  }

  /**
   * Earliest index "date added" per instrument over its current memberships (either index);
   * instruments without any date are absent. Used by the point-in-time universe of the historical
   * test (§30.5).
   */
  public Map<Long, LocalDate> addedOn() {
    Map<Long, LocalDate> out = new HashMap<>();
    jdbc.query(
        """
        SELECT instrument_id, min(added_on) AS added_on FROM index_constituent
        WHERE until IS NULL AND added_on IS NOT NULL GROUP BY instrument_id""",
        rs -> {
          out.put(rs.getLong("instrument_id"), rs.getDate("added_on").toLocalDate());
        });
    return out;
  }

  /** Ends the current membership; the row is kept as history. */
  public void close(IndexCode index, long instrumentId, LocalDate until) {
    jdbc.update(
        "UPDATE index_constituent SET until = ? WHERE index_code = ? AND instrument_id = ? AND until IS NULL",
        Date.valueOf(until),
        index.name(),
        instrumentId);
  }
}
