package org.jdkxx.trader.domain.signal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 一只标的一个判定日的四门判定结果（未经边沿、冷却、AI 抑制——那是 {@link SignalSuppression} 的事）。
 *
 * @param status      EVALUATED，或数据层面不予判定的原因
 * @param gates       四门结论，按门的顺序；不予判定时为空
 * @param indicators  判定日的指标取值（键序固定）
 * @param zones       回看期内的有效支撑区，按区底升序
 * @param hitZone     命中的支撑区（多个命中时取区顶最高者），没有则 null
 * @param exitPlan    出场预案；第四门不可判定时为 null
 * @param bonus       加分项（不影响通过与否）：斐波那契重合、MACD 柱为正
 */
public record SentinelEvaluation(
        String version,
        LocalDate asOf,
        Status status,
        String statusDetail,
        List<GateResult> gates,
        Map<String, Object> indicators,
        List<PriceZone> zones,
        PriceZone hitZone,
        ExitPlan exitPlan,
        Map<String, Object> bonus) {

    public enum Status {
        EVALUATED,
        /** 窗口内 K 线少于最低根数 */
        SKIPPED_INSUFFICIENT_BARS,
        /** 最新 K 线不是应有的交易日：以陈旧数据判定会产出形式正常的错误信号且不报错 */
        SKIPPED_STALE_DATA,
        /** 窗口内缺失的交易日超过容忍度（对照交易日历） */
        SKIPPED_DATA_GAP,
        /** 窗口内的股数变动事件缺比例，或遇到未实测的事件类型：价量口径算不出来 */
        SKIPPED_CORPORATE_ACTION
    }

    public SentinelEvaluation {
        gates = List.copyOf(gates);
        zones = List.copyOf(zones);
    }

    public static SentinelEvaluation skipped(String version, LocalDate asOf, Status status, String detail) {
        return new SentinelEvaluation(version, asOf, status, detail, List.of(), Map.of(), List.of(), null, null, Map.of());
    }

    public boolean allPassed() {
        return status == Status.EVALUATED && gates.stream().allMatch(GateResult::passed);
    }

    public int gatesPassed() {
        return (int) gates.stream().filter(GateResult::passed).count();
    }

    /** 首个未通过（FAIL 或 UNAVAILABLE）的门。 */
    public Optional<GateResult.Gate> firstBlockingGate() {
        return gates.stream().filter(g -> !g.passed()).map(GateResult::gate).findFirst();
    }
}
