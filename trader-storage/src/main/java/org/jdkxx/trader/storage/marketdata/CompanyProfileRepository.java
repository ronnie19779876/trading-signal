package org.jdkxx.trader.storage.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdkxx.trader.domain.CompanyProfile;
import org.jdkxx.trader.domain.Instrument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Company profile, stored in a jsonb column of the instrument table: the fields are scattered and
 * differ by market, so a separate table is not worth it. The broker's name/value pairs are stored
 * as they come; an unparseable stored value reads as "no profile".
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class CompanyProfileRepository {

  private final JdbcTemplate jdbc;
  private final ObjectMapper json = new ObjectMapper();

  public CompanyProfileRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void save(long instrumentId, CompanyProfile profile) {
    String value;
    try {
      value = json.writeValueAsString(profile.fields());
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("公司简介转 JSON 失败：" + profile.instrument(), e);
    }
    jdbc.update(
        "UPDATE instrument SET profile = ?::jsonb, profile_fetched_at = now() WHERE id = ?",
        new Object[] {value, instrumentId},
        new int[] {Types.VARCHAR, Types.BIGINT});
  }

  public Optional<CompanyProfile> find(Instrument instrument, long instrumentId) {
    List<String> rows =
        jdbc.queryForList(
            "SELECT profile::text FROM instrument WHERE id = ? AND profile IS NOT NULL",
            String.class,
            instrumentId);
    if (rows.isEmpty() || rows.getFirst() == null) {
      return Optional.empty();
    }
    try {
      Map<String, String> fields =
          json.readValue(
              rows.getFirst(),
              json.getTypeFactory()
                  .constructMapType(LinkedHashMap.class, String.class, String.class));
      return Optional.of(new CompanyProfile(instrument, fields));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return Optional.empty();
    }
  }

  /** When the profile was last refreshed, used for the monthly refresh. */
  public Optional<Instant> fetchedAt(long instrumentId) {
    List<java.sql.Timestamp> rows =
        jdbc.queryForList(
            "SELECT profile_fetched_at FROM instrument WHERE id = ?",
            java.sql.Timestamp.class,
            instrumentId);
    return rows.isEmpty() || rows.getFirst() == null
        ? Optional.empty()
        : Optional.of(rows.getFirst().toInstant());
  }
}
