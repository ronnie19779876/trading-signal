package org.jdkxx.trader.storage.marketdata;

import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.Instrument;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 财报期次与数据项。
 * 唯一键带期别：年报与四季报的期末可能是同一天（实测英伟达 2026/FY 与 2026/Q4 都是 01-24），
 * 只按期末去重会丢掉其中一份。取"最近一期"一律按期末日期排序，财年可能领先自然年，不能拿来排序。
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class FinancialRepository {

    /** 一只标的一类报表的最近期次，用于判断陈旧度。 */
    public record Latest(long instrumentId, String statement, LocalDate periodEnd) {
    }

    private final JdbcTemplate jdbc;

    public FinancialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入一只标的的若干期，返回写入的期数。数据项整期替换，避免券商改字段后留下孤儿行。 */
    public int upsertAll(long instrumentId, List<FinancialReport> reports) {
        int n = 0;
        for (FinancialReport r : reports) {
            if (r.periodEnd() == null || r.periodText() == null) {
                continue;   // 没有期末或期别就无法定位，跳过而不是编一个
            }
            Long reportId = jdbc.query("""
                    INSERT INTO financial_report (instrument_id, statement, period_end, period_text, fiscal_year,
                            currency, accounting_standards, auditor_report)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (instrument_id, statement, period_end, period_text) DO UPDATE SET
                        fiscal_year = EXCLUDED.fiscal_year, currency = EXCLUDED.currency,
                        accounting_standards = EXCLUDED.accounting_standards,
                        auditor_report = EXCLUDED.auditor_report, fetched_at = now()
                    RETURNING id""",
                    rs -> rs.next() ? rs.getLong(1) : null,
                    instrumentId, r.statement().name(), Date.valueOf(r.periodEnd()), r.periodText(),
                    r.fiscalYear() == 0 ? null : r.fiscalYear(), r.currency(), r.accountingStandards(), r.auditorReport());
            if (reportId == null) {
                continue;
            }
            jdbc.update("DELETE FROM financial_item WHERE report_id = ?", reportId);
            List<Object[]> args = new ArrayList<>(r.items().size());
            for (FinancialReport.Item i : r.items()) {
                args.add(new Object[] {reportId, i.fieldId(), i.name(), i.value(), i.yoy(), i.qoq()});
            }
            if (!args.isEmpty()) {
                jdbc.batchUpdate("INSERT INTO financial_item (report_id, field_id, field_name, value, yoy, qoq) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", args);
            }
            n++;
        }
        return n;
    }

    /** 一只标的一类报表的最近若干期，按期末从新到旧，带数据项。 */
    public List<FinancialReport> find(Instrument instrument, long instrumentId, FinancialStatement statement, int limit) {
        record Row(long id, LocalDate periodEnd, String periodText, int fiscalYear, String currency,
                   String standards, String auditor) {
        }
        List<Row> rows = jdbc.query("""
                SELECT id, period_end, period_text, fiscal_year, currency, accounting_standards, auditor_report
                FROM financial_report WHERE instrument_id = ? AND statement = ?
                ORDER BY period_end DESC, period_text DESC LIMIT ?""",
                (rs, i) -> new Row(rs.getLong("id"), rs.getDate("period_end").toLocalDate(), rs.getString("period_text"),
                        rs.getInt("fiscal_year"), rs.getString("currency"), rs.getString("accounting_standards"),
                        rs.getString("auditor_report")),
                instrumentId, statement.name(), limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<FinancialReport.Item>> items = new HashMap<>();
        String ids = String.join(",", rows.stream().map(r -> Long.toString(r.id())).toList());
        jdbc.query("SELECT report_id, field_id, field_name, value, yoy, qoq FROM financial_item "
                        + "WHERE report_id IN (" + ids + ") ORDER BY field_id",
                rs -> {
                    items.computeIfAbsent(rs.getLong("report_id"), k -> new ArrayList<>())
                            .add(new FinancialReport.Item(rs.getLong("field_id"), rs.getString("field_name"),
                                    rs.getBigDecimal("value"), rs.getBigDecimal("yoy"), rs.getBigDecimal("qoq")));
                });
        List<FinancialReport> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            out.add(new FinancialReport(instrument, statement, r.periodEnd(), r.fiscalYear(), r.periodText(),
                    r.currency(), r.standards(), r.auditor(), items.getOrDefault(r.id(), List.of())));
        }
        return out;
    }

    /** 每只标的每类报表的最近期末，用于陈旧度检查。 */
    public List<Latest> latestPeriods() {
        return jdbc.query("""
                SELECT instrument_id, statement, max(period_end) AS period_end
                FROM financial_report GROUP BY instrument_id, statement""",
                (rs, i) -> new Latest(rs.getLong("instrument_id"), rs.getString("statement"),
                        rs.getDate("period_end").toLocalDate()));
    }

    public long countReports() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM financial_report", Long.class);
        return n == null ? 0 : n;
    }
}
