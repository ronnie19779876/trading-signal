package org.jdkxx.trader.storage.selection;

import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 财报公告日（§25.6）。富途只给最近 20 条且含未来预定日期，<b>中间还会缺期</b>（2026-10-11 实测 KO 缺 2022/Q3~2023/Q1、
 * BRK.B 缺得更多）；缺的期由时点规则兜底。覆盖写入：预定日期改了就跟着改。
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionEarningsRepository {

    /**
     * 公告日与季报的匹配（只看公告日已回补成功的标的）。
     *
     * @param reports        期末不早于 from 的季报期数（利润表）
     * @param matched        其中按期别对上公告日的
     * @param beyondRule     对上了、但公告日晚于法定期限规则（期末 +1 后 Q1~Q3 加 40 天、Q4 加 60 天，落在非交易日顺延到下一交易日）的
     *                       ——规则用来兜底没有公告日的期，这类期多了说明兜底会偏早
     * @param pubBeforeEnd   公告日早于期末的（数据异常）
     * @param medianLagDays  期末到公告日天数的中位数
     */
    public record Matching(long reports, long matched, long beyondRule, long pubBeforeEnd, Double medianLagDays,
                           List<String> unmatchedSamples, List<String> beyondRuleSamples) {
    }

    private final JdbcTemplate jdbc;

    public SelectionEarningsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int upsertAll(long instrumentId, List<EarningsEvent> events) {
        List<Object[]> args = new ArrayList<>(events.size());
        for (EarningsEvent e : events) {
            args.add(new Object[] {instrumentId, e.periodText(), e.fiscalYear() == 0 ? null : e.fiscalYear(), Date.valueOf(e.pubDate()),
                    e.pubTimeText(), e.timing().name(), e.current()});
        }
        if (args.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate("""
                INSERT INTO selection_earnings_event (instrument_id, period_text, fiscal_year, pub_date, pub_time_text, timing, is_current)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (instrument_id, period_text) DO UPDATE SET fiscal_year = EXCLUDED.fiscal_year,
                    pub_date = EXCLUDED.pub_date, pub_time_text = EXCLUDED.pub_time_text, timing = EXCLUDED.timing,
                    is_current = EXCLUDED.is_current, fetched_at = now()""", args);
        return args.size();
    }

    /** 公告交易日落在 [from, to] 的标的（每日作业据此补取刚发的季报与估值）。 */
    public java.util.Set<Long> instrumentsWithPubBetween(LocalDate from, LocalDate to) {
        return new java.util.HashSet<>(jdbc.queryForList(
                "SELECT DISTINCT instrument_id FROM selection_earnings_event WHERE pub_date BETWEEN ? AND ?",
                Long.class, Date.valueOf(from), Date.valueOf(to)));
    }

    public Matching matching(LocalDate from, int sampleLimit) {
        String base = """
                FROM financial_report r
                JOIN instrument i ON i.id = r.instrument_id
                LEFT JOIN selection_earnings_event e ON e.instrument_id = r.instrument_id AND e.period_text = r.period_text
                WHERE r.statement = 'INCOME' AND r.period_text ~ '/Q[1-4]$' AND r.period_end >= ?
                  AND r.instrument_id IN (SELECT instrument_id FROM selection_backfill_state WHERE part = 'EARNINGS' AND status = 'OK')""";
        // 规则日落在周末或假日时取其后第一个交易日：富途给的是「生效交易日」，周六发的财报记成下周一
        // （BRK.B 2026/Q2：规则日 08-09 是周日，公告 08-10；不按交易日比会误报。2026-10-11 开发库实测）
        String beyond = "e.pub_date > (SELECT min(t.trade_date) FROM trading_day t WHERE t.market = 'US' AND t.trade_date >= "
                + "r.period_end + 1 + CASE WHEN r.period_text LIKE '%/Q4' THEN 60 ELSE 40 END)";
        Date f = Date.valueOf(from);
        Object[] head = jdbc.queryForObject("SELECT count(*) AS n, count(e.instrument_id) AS m, "
                        + "count(*) FILTER (WHERE " + beyond + ") AS late, count(*) FILTER (WHERE e.pub_date < r.period_end) AS early, "
                        + "percentile_cont(0.5) WITHIN GROUP (ORDER BY e.pub_date - r.period_end) AS lag " + base,
                (rs, n) -> new Object[] {rs.getLong("n"), rs.getLong("m"), rs.getLong("late"), rs.getLong("early"),
                        rs.getObject("lag") == null ? null : rs.getDouble("lag")}, f);
        List<String> unmatched = jdbc.query("SELECT i.symbol || ' ' || r.period_text || '@' || r.period_end AS s " + base
                + " AND e.instrument_id IS NULL ORDER BY r.period_end DESC, i.symbol LIMIT ?", (rs, n) -> rs.getString("s"), f, sampleLimit);
        List<String> late = jdbc.query("SELECT i.symbol || ' ' || r.period_text || ' 期末 ' || r.period_end || ' 公告 ' || e.pub_date AS s "
                + base + " AND " + beyond + " ORDER BY r.period_end DESC, i.symbol LIMIT ?", (rs, n) -> rs.getString("s"), f, sampleLimit);
        return new Matching((Long) head[0], (Long) head[1], (Long) head[2], (Long) head[3], (Double) head[4], unmatched, late);
    }
}
