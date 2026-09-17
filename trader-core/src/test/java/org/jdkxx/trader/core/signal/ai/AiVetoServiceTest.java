package org.jdkxx.trader.core.signal.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdkxx.trader.ai.AiProperties;
import org.jdkxx.trader.ai.veto.VetoClient;
import org.jdkxx.trader.ai.veto.VetoJudgment;
import org.jdkxx.trader.ai.veto.VetoJudgment.Evidence;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.domain.signal.SentinelEvaluation;
import org.jdkxx.trader.domain.signal.SignalSuppression.AiVerdict;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.signal.AiAnalysisRepository;
import org.jdkxx.trader.storage.signal.AiAnalysisRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiVetoServiceTest {

    private static final InstrumentRow ROW = new InstrumentRow(7, Market.US, "X", "X Corp", null, SecurityType.STOCK, 1, null,
            false, null, null, "RESOLVED");
    private static final SentinelEvaluation EVAL = SentinelEvaluation.skipped("sentinel-v1", LocalDate.of(2026, 9, 16),
            SentinelEvaluation.Status.EVALUATED, null);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T22:10:00Z"), ZoneOffset.UTC);

    private SignalPayloadBuilder payloads;
    private VetoClient client;
    private AiAnalysisRepository repo;

    @BeforeEach
    void setUp() {
        payloads = mock(SignalPayloadBuilder.class);
        client = mock(VetoClient.class);
        repo = mock(AiAnalysisRepository.class);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("valuation", Map.of("peStaticPercentile5y", 97.5));
        payload.put("financials", Map.of("quarters", List.of(Map.of("revenueYoyPct", -8.2))));
        when(payloads.build(any(), any(), any())).thenReturn(payload);
        when(client.model()).thenReturn("gpt-5.6-sol");
        when(client.reasoningEffort()).thenReturn("medium");
        when(repo.reusable(anyLong(), any(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(repo.insert(any())).thenReturn(99L);
    }

    private AiVetoService service(boolean enabled, int dailyLimit, String key) {
        AiProperties props = new AiProperties(key, "gpt-5.6-sol", null, Duration.ofSeconds(120), 2, enabled, dailyLimit,
                Duration.ofMinutes(15), "medium", 16000);
        return new AiVetoService(payloads, client, props, repo, new ObjectMapper(), CLOCK, ZoneId.of("America/New_York"));
    }

    private static VetoClient.Call ok(VetoJudgment j) {
        return new VetoClient.Call(VetoClient.Status.OK, j, "{}", "gpt-5.6-sol", "resp", 5000, 2000, 900, 400, 15000, null);
    }

    @Test
    void 只在开关打开的实盘判定里调用_补跑不调() {
        assertThat(service(true, 20, "k").enabledFor("LIVE")).isTrue();
        assertThat(service(true, 20, "k").enabledFor("BACKFILL")).isFalse();
        assertThat(service(false, 20, "k").enabledFor("LIVE")).isFalse();
    }

    @Test
    void 达到每日上限时不调用_记预算跳过并放行() {
        when(repo.callsSince(any())).thenReturn(20);

        AiVetoService.Outcome o = service(true, 20, "k").analyze(ROW, EVAL, "SIGNAL_VETO", 1L, null);

        assertThat(o.verdict()).isEqualTo(AiVerdict.ABSENT);
        verify(client, never()).analyze(anyString(), anyString());
        ArgumentCaptor<AiAnalysisRow> saved = ArgumentCaptor.forClass(AiAnalysisRow.class);
        verify(repo).insert(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo("SKIPPED_BUDGET");
    }

    @Test
    void 超过作业内时长预算时不调用() {
        AiVetoService.Outcome o = service(true, 20, "k").analyze(ROW, EVAL, "SIGNAL_VETO", 1L, CLOCK.instant().minusSeconds(1));

        assertThat(o.verdict()).isEqualTo(AiVerdict.ABSENT);
        verify(client, never()).analyze(anyString(), anyString());
    }

    @Test
    void 同一输入已有结论时复用不再调用() {
        AiAnalysisRow prior = new AiAnalysisRow(5, 7, "X", EVAL.asOf(), "SIGNAL_VETO", null, "sentinel-veto-v1", "gpt-5.6-sol",
                "medium", "h", "{}", "OK", null, null, "BEARISH", "HIGH", "VETO", "r", null, 2, 0, null, null, null, null, null,
                null, null, null, null);
        when(repo.reusable(anyLong(), any(), anyString(), anyString(), anyString())).thenReturn(Optional.of(prior));

        AiVetoService.Outcome o = service(true, 20, "k").analyze(ROW, EVAL, "SIGNAL_VETO", 1L, null);

        assertThat(o.verdict()).isEqualTo(AiVerdict.VETO);
        assertThat(o.analysisId()).isEqualTo(5);
        verify(client, never()).analyze(anyString(), anyString());
    }

    @Test
    void 调用失败按没有结论放行() {
        when(client.analyze(anyString(), anyString())).thenReturn(new VetoClient.Call(VetoClient.Status.TRUNCATED, null, null,
                "gpt-5.6-sol", "resp", 5000, 0, 200, 200, 7000, "响应不完整：max_output_tokens"));

        AiVetoService.Outcome o = service(true, 20, "k").analyze(ROW, EVAL, "SIGNAL_VETO", 1L, null);

        assertThat(o.verdict()).isEqualTo(AiVerdict.ABSENT);
    }

    @Test
    void 看空有把握且两条证据核对通过时否决_编造的证据不算() {
        List<Evidence> good = List.of(new Evidence("valuation.peStaticPercentile5y", "97.5", "估值极端"),
                new Evidence("financials.quarters[0].revenueYoyPct", "-8.2", "收入下滑"));
        when(client.analyze(anyString(), anyString())).thenReturn(ok(new VetoJudgment(VetoJudgment.Stance.AVOID,
                VetoJudgment.Confidence.MEDIUM, "s", List.of(), good, List.of(), List.of(), "收入下滑且估值极端")));
        assertThat(service(true, 20, "k").analyze(ROW, EVAL, "SIGNAL_VETO", 1L, null).verdict()).isEqualTo(AiVerdict.VETO);

        List<Evidence> fake = List.of(good.get(0), new Evidence("financials.quarters[0].revenueYoyPct", "-30", "编造"));
        when(client.analyze(anyString(), anyString())).thenReturn(ok(new VetoJudgment(VetoJudgment.Stance.AVOID,
                VetoJudgment.Confidence.MEDIUM, "s", List.of(), fake, List.of(), List.of(), "收入大跌")));
        assertThat(service(true, 20, "k").analyze(ROW, EVAL, "SIGNAL_VETO", 1L, null).verdict()).isEqualTo(AiVerdict.ALLOW);
    }
}
