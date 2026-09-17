package org.jdkxx.trader.storage.signal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class AiAnalysisRepository {

    private static final String SELECT = "SELECT a.*, i.symbol FROM ai_analysis a JOIN instrument i ON i.id = a.instrument_id";

    private static final RowMapper<AiAnalysisRow> MAPPER = (rs, n) -> new AiAnalysisRow(
            rs.getLong("id"), rs.getLong("instrument_id"), rs.getString("symbol"), rs.getDate("trade_date").toLocalDate(),
            rs.getString("purpose"), (Long) rs.getObject("signal_id"), rs.getString("prompt_version"), rs.getString("model"),
            rs.getString("reasoning_effort"), rs.getString("input_hash"), rs.getString("input"), rs.getString("status"),
            rs.getString("judgment"), rs.getString("output_text"), rs.getString("stance"), rs.getString("confidence"),
            rs.getString("verdict"), rs.getString("verdict_reason"), rs.getString("checks"),
            (Integer) rs.getObject("verified_bear"), (Integer) rs.getObject("unverified"), rs.getString("error"),
            rs.getString("response_id"), (Integer) rs.getObject("input_tokens"), (Integer) rs.getObject("cached_tokens"),
            (Integer) rs.getObject("output_tokens"), (Integer) rs.getObject("reasoning_tokens"), (Integer) rs.getObject("latency_ms"),
            (Long) rs.getObject("job_run_id"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public AiAnalysisRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入一行，返回 id。id / symbol / createdAt 忽略。 */
    public long insert(AiAnalysisRow r) {
        return jdbc.queryForObject("""
                INSERT INTO ai_analysis (instrument_id, trade_date, purpose, signal_id, prompt_version, model, reasoning_effort,
                        input_hash, input, status, judgment, output_text, stance, confidence, verdict, verdict_reason, checks,
                        verified_bear, unverified, error, response_id, input_tokens, cached_tokens, output_tokens,
                        reasoning_tokens, latency_ms, job_run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id""", Long.class,
                r.instrumentId(), Date.valueOf(r.tradeDate()), r.purpose(), r.signalId(), r.promptVersion(), r.model(),
                r.reasoningEffort(), r.inputHash(), r.input(), r.status(), r.judgment(), r.outputText(), r.stance(),
                r.confidence(), r.verdict(), r.verdictReason(), r.checks(), r.verifiedBear(), r.unverified(), r.error(),
                r.responseId(), r.inputTokens(), r.cachedTokens(), r.outputTokens(), r.reasoningTokens(), r.latencyMs(),
                r.jobRunId());
    }

    /** 同一标的、判定日、提示词、模型、输入哈希的最近一条 OK（重跑复用，不再计费）。 */
    public Optional<AiAnalysisRow> reusable(long instrumentId, LocalDate date, String promptVersion, String model, String inputHash) {
        return jdbc.query(SELECT + " WHERE a.instrument_id = ? AND a.trade_date = ? AND a.prompt_version = ? AND a.model = ?"
                        + " AND a.input_hash = ? AND a.status = 'OK' ORDER BY a.id DESC LIMIT 1", MAPPER,
                instrumentId, Date.valueOf(date), promptVersion, model, inputHash).stream().findFirst();
    }

    /** 自 since 起真正发出的模型调用次数（跳过与输入构建失败的不算）。 */
    public int callsSince(Instant since) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM ai_analysis WHERE created_at >= ? AND status NOT IN ('SKIPPED_BUDGET', 'FAILED_DATA')""",
                Integer.class, Timestamp.from(since));
        return n == null ? 0 : n;
    }

    public void linkSignal(long analysisId, long signalId) {
        jdbc.update("UPDATE ai_analysis SET signal_id = ? WHERE id = ? AND signal_id IS NULL", signalId, analysisId);
    }

    public Optional<AiAnalysisRow> find(long id) {
        return jdbc.query(SELECT + " WHERE a.id = ?", MAPPER, id).stream().findFirst();
    }

    /** 按创建时间倒序；status / symbol 可空。 */
    public List<AiAnalysisRow> list(Instant from, Instant to, String status, String symbol, int limit) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE a.created_at >= ? AND a.created_at < ?");
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(from), Timestamp.from(to)));
        if (status != null) {
            sql.append(" AND a.status = ?");
            args.add(status);
        }
        if (symbol != null) {
            sql.append(" AND i.symbol = ?");
            args.add(symbol);
        }
        sql.append(" ORDER BY a.id DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 500)));
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** 某个判定日的分析（审计用）。 */
    public List<AiAnalysisRow> onTradeDate(LocalDate date) {
        return jdbc.query(SELECT + " WHERE a.trade_date = ? ORDER BY a.id", MAPPER, Date.valueOf(date));
    }

    public record DailyUsage(LocalDate day, int rows, int calls, int ok, int failed, int skipped, int vetoes,
                             long inputTokens, long cachedTokens, long outputTokens, long reasoningTokens) {
    }

    /** 按 zone 的自然日汇总。 */
    public List<DailyUsage> usage(Instant from, Instant to, String zone) {
        return jdbc.query("""
                SELECT (created_at AT TIME ZONE CAST(? AS text))::date AS day, count(*) AS rows,
                       count(*) FILTER (WHERE status NOT IN ('SKIPPED_BUDGET', 'FAILED_DATA')) AS calls,
                       count(*) FILTER (WHERE status = 'OK') AS ok,
                       count(*) FILTER (WHERE status IN ('REFUSED', 'TRUNCATED', 'INVALID', 'FAILED', 'FAILED_DATA')) AS failed,
                       count(*) FILTER (WHERE status = 'SKIPPED_BUDGET') AS skipped,
                       count(*) FILTER (WHERE verdict = 'VETO') AS vetoes,
                       coalesce(sum(input_tokens), 0) AS it, coalesce(sum(cached_tokens), 0) AS ct,
                       coalesce(sum(output_tokens), 0) AS ot, coalesce(sum(reasoning_tokens), 0) AS rt
                FROM ai_analysis WHERE created_at >= ? AND created_at < ?
                GROUP BY 1 ORDER BY 1 DESC""",
                (rs, n) -> new DailyUsage(rs.getDate("day").toLocalDate(), rs.getInt("rows"), rs.getInt("calls"), rs.getInt("ok"),
                        rs.getInt("failed"), rs.getInt("skipped"), rs.getInt("vetoes"), rs.getLong("it"), rs.getLong("ct"),
                        rs.getLong("ot"), rs.getLong("rt")),
                zone, Timestamp.from(from), Timestamp.from(to));
    }
}
