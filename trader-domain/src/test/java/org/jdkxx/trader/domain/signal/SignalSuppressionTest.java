package org.jdkxx.trader.domain.signal;

import org.jdkxx.trader.domain.signal.SignalSuppression.AiVerdict;
import org.jdkxx.trader.domain.signal.SignalSuppression.Outcome;
import org.jdkxx.trader.domain.signal.SignalSuppression.PreviousDay;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignalSuppressionTest {

    private static final SentinelThresholds TH = SentinelThresholds.V1;

    @Test
    void 未全过不是信号() {
        assertThat(SignalSuppression.beforeAi(false, PreviousDay.NOT_PASSED, null, TH)).isEqualTo(Outcome.NO_SIGNAL);
    }

    @Test
    void 上一交易日已全过则按边沿抑制() {
        assertThat(SignalSuppression.beforeAi(true, PreviousDay.PASSED, null, TH)).isEqualTo(Outcome.SUPPRESSED_EDGE);
    }

    @Test
    void 上一交易日被AI否决仍算边沿() {
        assertThat(SignalSuppression.beforeAi(true, PreviousDay.BLOCKED_BY_AI, null, TH)).isEqualTo(Outcome.PENDING_AI);
    }

    @Test
    void 冷却期按交易日数判断_满5天放行() {
        assertThat(SignalSuppression.beforeAi(true, PreviousDay.NOT_PASSED, 4, TH)).isEqualTo(Outcome.SUPPRESSED_COOLDOWN);
        assertThat(SignalSuppression.beforeAi(true, PreviousDay.NOT_PASSED, 5, TH)).isEqualTo(Outcome.PENDING_AI);
    }

    @Test
    void AI只有否决权且没有结论不阻断() {
        assertThat(SignalSuppression.afterAi(Outcome.PENDING_AI, AiVerdict.VETO)).isEqualTo(Outcome.BLOCKED_BY_AI);
        assertThat(SignalSuppression.afterAi(Outcome.PENDING_AI, AiVerdict.ALLOW)).isEqualTo(Outcome.SIGNAL);
        assertThat(SignalSuppression.afterAi(Outcome.PENDING_AI, AiVerdict.ABSENT)).isEqualTo(Outcome.SIGNAL);
        assertThat(SignalSuppression.afterAi(Outcome.SUPPRESSED_EDGE, AiVerdict.ALLOW)).isEqualTo(Outcome.SUPPRESSED_EDGE);
    }
}
