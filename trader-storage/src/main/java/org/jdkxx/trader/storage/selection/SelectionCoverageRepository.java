package org.jdkxx.trader.storage.selection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/** 量化选股对共用表的只读覆盖统计（§25.9 的 6.1 验收）。 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionCoverageRepository {

    /**
     * 季报深度：季报已回补成功的标的里，利润表最早一季早于 {@code needBy} 的有几只。
     *
     * @param shallowSamples 不够深的标的（代码 + 最早一季期末），新上市或分拆的本来就短
     */
    public record FinancialDepth(long instruments, long deep, LocalDate medianEarliest, List<String> shallowSamples) {
    }

    private final JdbcTemplate jdbc;

    public SelectionCoverageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public FinancialDepth financialDepth(LocalDate needBy, int sampleLimit) {
        String with = """
                WITH d AS (
                    SELECT r.instrument_id, min(r.period_end) AS earliest
                    FROM financial_report r
                    WHERE r.statement = 'INCOME' AND r.period_text ~ '/Q[1-4]$'
                      AND r.instrument_id IN (SELECT instrument_id FROM selection_backfill_state WHERE part = 'FINANCIALS' AND status = 'OK')
                    GROUP BY r.instrument_id)
                """;
        Date need = Date.valueOf(needBy);
        Object[] head = jdbc.queryForObject(with + """
                SELECT count(*) AS n, count(*) FILTER (WHERE earliest <= ?) AS deep,
                       percentile_disc(0.5) WITHIN GROUP (ORDER BY earliest) AS med FROM d""",
                (rs, n) -> new Object[] {rs.getLong("n"), rs.getLong("deep"),
                        rs.getDate("med") == null ? null : rs.getDate("med").toLocalDate()}, need);
        List<String> shallow = jdbc.query(with + """
                SELECT i.symbol || ' 自 ' || d.earliest AS s FROM d JOIN instrument i ON i.id = d.instrument_id
                WHERE d.earliest > ? ORDER BY d.earliest DESC, i.symbol LIMIT ?""",
                (rs, n) -> rs.getString("s"), need, sampleLimit);
        return new FinancialDepth((Long) head[0], (Long) head[1], (LocalDate) head[2], shallow);
    }
}
