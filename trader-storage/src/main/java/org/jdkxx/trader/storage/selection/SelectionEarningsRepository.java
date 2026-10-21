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
     * 公告日与季报的匹配（只看公告日已回补成功的标的），口径与打分一致：<b>按日期就近</b>——期末后 75 天内的第一个公告交易日
     * （3.3.0 前按期别文本匹配，与模型口径不一致，1~2 月财年的零售商会显示成假的「对不上」）。
     *
     * @param reports        期末不早于 from 的季报期数（利润表）
     * @param matched        其中 75 天内有公告日的（其余由兜底规则给可用日）
     * @param beyondRule     匹配上、但公告日晚于法定期限（期末 +1 后 Q1~Q3 加 40 天、Q4 加 60 天，落在非交易日顺延）的——兜底只用规则会偏早，所以兜底取 max(规则, 已观测最大滞后)
     * @param medianLagDays  期末到公告日天数的中位数
     */
    public record Matching(long reports, long matched, long beyondRule, Double medianLagDays,
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

    /** 一次公告对应的那一季还缺哪几类报表（INCOME / BALANCE_SHEET / CASH_FLOW）。 */
    public record IncompleteFinancials(long instrumentId, String symbol, LocalDate pubDate, List<String> missing) {
    }

    /**
     * 公告交易日落在 [from, to]、而该季三类报表（利润表、资产负债表、现金流量表）<b>还没到齐</b>的（3.4.6）。
     * 「这一季的报表」与 {@link #matching} 同一就近规则：期末在公告日之前、且公告在期末后 75 天内（季报行 {@code /Q[1-4]}）。
     *
     * <p>起因（2026-10-22 生产 + 开发实例实测）：富途新一季<b>利润表先到、另外两类晚到</b>
     * （JPM、BAC、GS 公告一周后才有新一季资产负债表，现金流还没有；AZO 公告 29 天仍缺现金流），
     * 而每日作业原先只在公告后 6 天内重取，晚到的报表就再也取不到了。
     */
    public List<IncompleteFinancials> incompleteFinancials(LocalDate from, LocalDate to) {
        // 普通字符串拼接、显式前导空格：文本块会剥掉行首空白（3.4.0 的 instrument_idWHERE 就是这么来的）
        String sql = "SELECT e.instrument_id, i.symbol, e.pub_date, st.statement"
                + " FROM selection_earnings_event e"
                + " JOIN instrument i ON i.id = e.instrument_id"
                + " CROSS JOIN (VALUES ('INCOME'), ('BALANCE_SHEET'), ('CASH_FLOW')) AS st(statement)"
                + " WHERE e.pub_date BETWEEN ? AND ?"
                + "   AND NOT EXISTS (SELECT 1 FROM financial_report r"
                + "                   WHERE r.instrument_id = e.instrument_id AND r.statement = st.statement"
                + "                     AND r.period_text ~ '/Q[1-4]$'"
                + "                     AND e.pub_date > r.period_end + 1 AND e.pub_date <= r.period_end + 1 + 75)"
                + " ORDER BY i.symbol, e.pub_date, st.statement";
        java.util.Map<String, IncompleteFinancials> out = new java.util.LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            long id = rs.getLong(1);
            LocalDate pub = rs.getDate(3).toLocalDate();
            out.computeIfAbsent(id + "|" + pub, k -> {
                try {
                    return new IncompleteFinancials(id, rs.getString(2), pub, new ArrayList<>());
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }).missing().add(rs.getString(4));
        }, Date.valueOf(from), Date.valueOf(to));
        return out.values().stream()
                .map(x -> new IncompleteFinancials(x.instrumentId(), x.symbol(), x.pubDate(), List.copyOf(x.missing())))
                .toList();
    }

    public Matching matching(LocalDate from, int sampleLimit) {
        String base = """
                WITH base AS (
                    SELECT r.instrument_id, r.period_text, r.period_end, i.symbol,
                           (SELECT min(e.pub_date) FROM selection_earnings_event e WHERE e.instrument_id = r.instrument_id
                              AND e.pub_date > r.period_end + 1 AND e.pub_date <= r.period_end + 1 + 75) AS pub
                    FROM financial_report r JOIN instrument i ON i.id = r.instrument_id
                    WHERE r.statement = 'INCOME' AND r.period_text ~ '/Q[1-4]$' AND r.period_end >= ?
                      AND r.instrument_id IN (SELECT instrument_id FROM selection_backfill_state WHERE part = 'EARNINGS' AND status = 'OK'))
                """;
        // 规则日落在周末或假日时取其后第一个交易日：富途给的是「生效交易日」，周六发的财报记成下周一
        String beyond = "pub > (SELECT min(t.trade_date) FROM trading_day t WHERE t.market = 'US' AND t.trade_date >= "
                + "period_end + 1 + CASE WHEN period_text LIKE '%/Q4' THEN 60 ELSE 40 END)";
        Date f = Date.valueOf(from);
        Object[] head = jdbc.queryForObject(base + "SELECT count(*) AS n, count(pub) AS m, count(*) FILTER (WHERE " + beyond + ") AS late, "
                        + "percentile_cont(0.5) WITHIN GROUP (ORDER BY pub - period_end) AS lag FROM base",
                (rs, n) -> new Object[] {rs.getLong("n"), rs.getLong("m"), rs.getLong("late"),
                        rs.getObject("lag") == null ? null : rs.getDouble("lag")}, f);
        List<String> unmatched = jdbc.query(base + "SELECT symbol || ' ' || period_text || '@' || period_end AS s FROM base "
                + "WHERE pub IS NULL ORDER BY period_end DESC, symbol LIMIT ?", (rs, n) -> rs.getString("s"), f, sampleLimit);
        List<String> late = jdbc.query(base + "SELECT symbol || ' ' || period_text || ' 期末 ' || period_end || ' 公告 ' || pub AS s FROM base "
                + "WHERE " + beyond + " ORDER BY period_end DESC, symbol LIMIT ?", (rs, n) -> rs.getString("s"), f, sampleLimit);
        return new Matching((Long) head[0], (Long) head[1], (Long) head[2], (Double) head[3], unmatched, late);
    }
}
