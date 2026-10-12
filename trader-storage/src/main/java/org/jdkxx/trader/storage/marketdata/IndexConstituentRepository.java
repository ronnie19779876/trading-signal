package org.jdkxx.trader.storage.marketdata;

import org.jdkxx.trader.domain.IndexCode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class IndexConstituentRepository {

    private static final RowMapper<ConstituentRow> MAPPER = (rs, i) -> new ConstituentRow(
            IndexCode.valueOf(rs.getString("index_code")), rs.getLong("instrument_id"), rs.getString("sector"),
            rs.getString("sub_industry"), rs.getString("classification"), rs.getDate("since").toLocalDate(),
            rs.getDate("until") == null ? null : rs.getDate("until").toLocalDate(), rs.getString("source"));

    private final JdbcTemplate jdbc;

    public IndexConstituentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ConstituentRow> current(IndexCode index) {
        return jdbc.query("SELECT * FROM index_constituent WHERE index_code = ? AND until IS NULL", MAPPER, index.name());
    }

    public List<ConstituentRow> currentAll() {
        return jdbc.query("SELECT * FROM index_constituent WHERE until IS NULL", MAPPER);
    }

    /**
     * 某一天有效的成分股：since ≤ d 且（未退出或 until 晚于 d）。退出那天记作 until，当天已不算成员（与同步当天的评估口径一致）。
     * <b>首次同步之前的成员关系没有记录</b>：首次同步入库的那批（since = 表里最早的 since）视为一直是成员，否则审计更早的日期会得到空集。
     */
    public List<ConstituentRow> validOn(LocalDate d) {
        return jdbc.query("""
                SELECT * FROM index_constituent
                WHERE (since <= ? OR since = (SELECT min(since) FROM index_constituent)) AND (until IS NULL OR until > ?)""",
                MAPPER, Date.valueOf(d), Date.valueOf(d));
    }

    public List<ConstituentRow> historyOf(long instrumentId) {
        return jdbc.query("SELECT * FROM index_constituent WHERE instrument_id = ? ORDER BY since", MAPPER, instrumentId);
    }

    public void add(IndexCode index, long instrumentId, String sector, String subIndustry, LocalDate since, String source) {
        jdbc.update("""
                INSERT INTO index_constituent (index_code, instrument_id, sector, sub_industry, classification, since, source)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (index_code, instrument_id, since) DO UPDATE SET sector = EXCLUDED.sector,
                       sub_industry = EXCLUDED.sub_industry, until = NULL, source = EXCLUDED.source""",
                index.name(), instrumentId, sector, subIndustry, index.classification(), Date.valueOf(since), source);
    }

    public void updateSector(IndexCode index, long instrumentId, String sector, String subIndustry) {
        jdbc.update("UPDATE index_constituent SET sector = ?, sub_industry = ? WHERE index_code = ? AND instrument_id = ? AND until IS NULL",
                sector, subIndustry, index.name(), instrumentId);
    }

    public void close(IndexCode index, long instrumentId, LocalDate until) {
        jdbc.update("UPDATE index_constituent SET until = ? WHERE index_code = ? AND instrument_id = ? AND until IS NULL",
                Date.valueOf(until), index.name(), instrumentId);
    }
}
