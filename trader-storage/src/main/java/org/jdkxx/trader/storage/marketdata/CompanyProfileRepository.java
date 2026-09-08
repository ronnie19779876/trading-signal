package org.jdkxx.trader.storage.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdkxx.trader.domain.CompanyProfile;
import org.jdkxx.trader.domain.Instrument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 公司简介，存在 instrument 表的 jsonb 列上：字段零散且随市场不同，没有必要单独建表。 */
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
        jdbc.update("UPDATE instrument SET profile = ?::jsonb, profile_fetched_at = now() WHERE id = ?",
                new Object[] {value, instrumentId}, new int[] {Types.VARCHAR, Types.BIGINT});
    }

    public Optional<CompanyProfile> find(Instrument instrument, long instrumentId) {
        List<String> rows = jdbc.queryForList("SELECT profile::text FROM instrument WHERE id = ? AND profile IS NOT NULL",
                String.class, instrumentId);
        if (rows.isEmpty() || rows.getFirst() == null) {
            return Optional.empty();
        }
        try {
            Map<String, String> fields = json.readValue(rows.getFirst(),
                    json.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
            return Optional.of(new CompanyProfile(instrument, fields));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /** 简介刷新时刻，用于按月刷新。 */
    public Optional<Instant> fetchedAt(long instrumentId) {
        List<java.sql.Timestamp> rows = jdbc.queryForList(
                "SELECT profile_fetched_at FROM instrument WHERE id = ?", java.sql.Timestamp.class, instrumentId);
        return rows.isEmpty() || rows.getFirst() == null ? Optional.empty() : Optional.of(rows.getFirst().toInstant());
    }
}
