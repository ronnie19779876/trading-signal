package org.jdkxx.trader.core.signal.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.jdkxx.trader.ai.AiProperties;
import org.jdkxx.trader.ai.veto.EvidenceVerifier;
import org.jdkxx.trader.ai.veto.Prompts;
import org.jdkxx.trader.ai.veto.VetoClient;
import org.jdkxx.trader.ai.veto.VetoRule;
import org.jdkxx.trader.domain.signal.SentinelEvaluation;
import org.jdkxx.trader.domain.signal.SignalSuppression.AiVerdict;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.signal.AiAnalysisRepository;
import org.jdkxx.trader.storage.signal.AiAnalysisRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Second opinion from the model (veto power only): build the input, reuse an existing OK verdict
 * for the identical input, check the budget, call, verify each piece of evidence, apply the veto
 * rule, persist. Any failure returns ABSENT (no verdict never blocks a signal) and still writes a
 * row explaining why.
 *
 * <p>Runs on the evaluation job thread (or a REST thread for manual analysis); the model call is
 * synchronous.
 */
public class AiVetoService {

  private static final Logger log = LoggerFactory.getLogger(AiVetoService.class);

  public record Outcome(AiVerdict verdict, Long analysisId, String stance) {
    static Outcome absent(Long id) {
      return new Outcome(AiVerdict.ABSENT, id, null);
    }
  }

  private final SignalPayloadBuilder payloads;
  private final VetoClient client;
  private final AiProperties props;
  private final AiAnalysisRepository analyses;
  private final ObjectMapper json;
  private final Clock clock;
  private final ZoneId zone;

  public AiVetoService(
      SignalPayloadBuilder payloads,
      VetoClient client,
      AiProperties props,
      AiAnalysisRepository analyses,
      ObjectMapper json,
      Clock clock,
      ZoneId zone) {
    this.payloads = payloads;
    this.client = client;
    this.props = props;
    this.analyses = analyses;
    this.json = json;
    this.clock = clock;
    this.zone = zone;
  }

  /**
   * Whether the evaluation job should call the model for candidates: the switch is on and it is a
   * live judgement (back-fills do not call: the model's training data contains information from
   * after the judgement date).
   */
  public boolean enabledFor(String origin) {
    return props.signalVetoEnabled() && "LIVE".equals(origin);
  }

  public Instant jobDeadline() {
    return clock.instant().plus(props.jobBudget());
  }

  /**
   * @param deadline no new call is started after this instant (total AI time budget within the
   *     job); null for manual analysis
   */
  public Outcome analyze(
      InstrumentRow row, SentinelEvaluation e, String purpose, Long jobRunId, Instant deadline) {
    LocalDate today = LocalDate.now(clock.withZone(zone));
    String version = Prompts.VERSION;
    String model = client.model();
    String input;
    try {
      input = json.writeValueAsString(payloads.build(row, e, today));
    } catch (Exception ex) {
      log.warn("{} {} 构建模型输入失败：{}", row.symbol(), e.asOf(), ex.toString());
      long id =
          analyses.insert(
              row(
                  row,
                  e,
                  purpose,
                  version,
                  model,
                  "-",
                  "{}",
                  "FAILED_DATA",
                  null,
                  "ABSENT",
                  null,
                  "构建输入失败：" + ex.getMessage(),
                  null,
                  jobRunId));
      return Outcome.absent(id);
    }
    // Identical input (same instrument, date, prompt version, model and input hash) with an OK
    // verdict is reused: no extra cost, and the verdict cannot flip between re-runs of the same day
    String hash = sha256(input);
    var reused = analyses.reusable(row.id(), e.asOf(), version, model, hash);
    if (reused.isPresent()) {
      AiAnalysisRow r = reused.get();
      log.info("{} {} 复用模型结论 #{}（{}）", row.symbol(), e.asOf(), r.id(), r.verdict());
      return new Outcome(
          AiVerdict.valueOf("VETO".equals(r.verdict()) ? "VETO" : "ALLOW"), r.id(), r.stance());
    }

    // Budget: API key configured, in-job time budget, and the per-day call cap (US/Eastern day,
    // manual calls included).
    // Rows that were skipped or failed to build their input do not count towards the cap (see
    // callsSince)
    String skip = null;
    if (!props.configured()) {
      skip = "未配置 trader.ai.api-key";
    } else if (deadline != null && clock.instant().isAfter(deadline)) {
      skip = "超过作业内 AI 总时长 " + props.jobBudget();
    } else {
      Instant dayStart = today.atStartOfDay(zone).toInstant();
      int calls = analyses.callsSince(dayStart);
      if (calls >= props.dailyCallLimit()) {
        skip = "今天已调用 " + calls + " 次，达到每日上限 " + props.dailyCallLimit();
      }
    }
    if (skip != null) {
      long id =
          analyses.insert(
              row(
                  row,
                  e,
                  purpose,
                  version,
                  model,
                  hash,
                  input,
                  "SKIPPED_BUDGET",
                  null,
                  "ABSENT",
                  null,
                  skip,
                  null,
                  jobRunId));
      return Outcome.absent(id);
    }

    VetoClient.Call call = client.analyze(version, input);
    if (call.status() != VetoClient.Status.OK) {
      log.warn("{} {} 模型调用 {}：{}", row.symbol(), e.asOf(), call.status(), call.error());
      long id =
          analyses.insert(
              row(
                  row,
                  e,
                  purpose,
                  version,
                  model,
                  hash,
                  input,
                  call.status().name(),
                  null,
                  "ABSENT",
                  null,
                  call.error(),
                  call,
                  jobRunId));
      return Outcome.absent(id);
    }
    try {
      JsonNode tree = json.readTree(input);
      List<EvidenceVerifier.Checked> checks = EvidenceVerifier.check(tree, call.judgment());
      VetoRule.Decision d = VetoRule.decide(call.judgment(), checks);
      AiAnalysisRow base =
          row(
              row,
              e,
              purpose,
              version,
              model,
              hash,
              input,
              "OK",
              call.judgment(),
              d.verdict().name(),
              d.reason(),
              null,
              call,
              jobRunId);
      long id =
          analyses.insert(
              new AiAnalysisRow(
                  0,
                  base.instrumentId(),
                  null,
                  base.tradeDate(),
                  base.purpose(),
                  null,
                  base.promptVersion(),
                  base.model(),
                  base.reasoningEffort(),
                  base.inputHash(),
                  base.input(),
                  base.status(),
                  base.judgment(),
                  base.outputText(),
                  base.stance(),
                  base.confidence(),
                  base.verdict(),
                  base.verdictReason(),
                  json.writeValueAsString(checks),
                  d.verifiedBear(),
                  d.unverified(),
                  null,
                  base.responseId(),
                  base.inputTokens(),
                  base.cachedTokens(),
                  base.outputTokens(),
                  base.reasoningTokens(),
                  base.latencyMs(),
                  jobRunId,
                  null));
      log.info(
          "{} {} 模型结论 {}/{} → {}（{}）",
          row.symbol(),
          e.asOf(),
          call.judgment().stance(),
          call.judgment().confidence(),
          d.verdict(),
          d.reason());
      return new Outcome(
          d.verdict() == VetoRule.Verdict.VETO ? AiVerdict.VETO : AiVerdict.ALLOW,
          id,
          call.judgment().stance().name());
    } catch (Exception ex) {
      log.warn("{} {} 模型结论处理失败：{}", row.symbol(), e.asOf(), ex.toString());
      long id =
          analyses.insert(
              row(
                  row,
                  e,
                  purpose,
                  version,
                  model,
                  hash,
                  input,
                  "INVALID",
                  null,
                  "ABSENT",
                  null,
                  "结论处理失败：" + ex.getMessage(),
                  call,
                  jobRunId));
      return Outcome.absent(id);
    }
  }

  public void linkSignal(Long analysisId, long signalId) {
    if (analysisId != null) {
      analyses.linkSignal(analysisId, signalId);
    }
  }

  private AiAnalysisRow row(
      InstrumentRow row,
      SentinelEvaluation e,
      String purpose,
      String version,
      String model,
      String hash,
      String input,
      String status,
      Object judgment,
      String verdict,
      String reason,
      String error,
      VetoClient.Call call,
      Long jobRunId) {
    String judgmentJson = null;
    if (judgment != null) {
      try {
        judgmentJson = json.writeValueAsString(judgment);
      } catch (Exception ignored) {
        judgmentJson = null;
      }
    }
    var j = call == null ? null : call.judgment();
    return new AiAnalysisRow(
        0,
        row.id(),
        row.symbol(),
        e.asOf(),
        purpose,
        null,
        version,
        model,
        client.reasoningEffort(),
        hash,
        input,
        status,
        judgmentJson,
        call == null ? null : call.rawOutput(),
        j == null ? null : j.stance().name(),
        j == null ? null : j.confidence().name(),
        verdict,
        reason,
        null,
        null,
        null,
        error,
        call == null ? null : call.responseId(),
        call == null ? null : (int) call.inputTokens(),
        call == null ? null : (int) call.cachedTokens(),
        call == null ? null : (int) call.outputTokens(),
        call == null ? null : (int) call.reasoningTokens(),
        call == null ? null : (int) call.latencyMs(),
        jobRunId,
        null);
  }

  static String sha256(String s) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Configuration summary for display (no secrets). */
  public Map<String, Object> settings() {
    return Map.of(
        "configured",
        props.configured(),
        "model",
        props.model(),
        "signalVetoEnabled",
        props.signalVetoEnabled(),
        "dailyCallLimit",
        props.dailyCallLimit(),
        "jobBudget",
        props.jobBudget().toString(),
        "reasoningEffort",
        props.reasoningEffort(),
        "promptVersion",
        Prompts.VERSION);
  }
}
