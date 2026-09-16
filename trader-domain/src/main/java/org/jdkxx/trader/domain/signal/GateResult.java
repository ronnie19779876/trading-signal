package org.jdkxx.trader.domain.signal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一道判定门的结论。{@code criteria} 是代入了数值的判据原文，{@code values} 是代入值（键序固定），
 * 两者都随评估落库，支持逐门复核。
 */
public record GateResult(Gate gate, Verdict verdict, String criteria, Map<String, Object> values) {

    public enum Gate {
        /** 一 趋势：是否参与 */
        TREND,
        /** 二 定位：入场区间 */
        LOCATION,
        /** 三 触发：入场条件 */
        TRIGGER,
        /** 四 风控：止损距离 */
        RISK
    }

    /** UNAVAILABLE = 数据不足以判定，<b>不等于通过</b>。 */
    public enum Verdict {
        PASS, FAIL, UNAVAILABLE
    }

    public GateResult {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public boolean passed() {
        return verdict == Verdict.PASS;
    }
}
