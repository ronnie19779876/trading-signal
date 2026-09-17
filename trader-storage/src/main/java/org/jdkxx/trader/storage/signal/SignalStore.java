package org.jdkxx.trader.storage.signal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 一只标的一天的评估结果整体写入：评估行、信号（若有）、两个出场变体的账本行在同一个事务里，
 * 不会出现"有信号没账本"或"有评估没信号"的半截状态。
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SignalStore {

    private final SignalEvaluationRepository evaluations;
    private final EntrySignalRepository signals;
    private final SignalTrackRepository tracks;

    public SignalStore(SignalEvaluationRepository evaluations, EntrySignalRepository signals, SignalTrackRepository tracks) {
        this.evaluations = evaluations;
        this.signals = signals;
        this.tracks = tracks;
    }

    /**
     * @param signal     为 null 表示当天不是信号
     * @param trackStops 变体 → 该变体的止损（判定日口径）；signal 为 null 时忽略
     * @return 新建的信号 id；没有信号或信号已存在（重跑）时为 null
     */
    @Transactional
    public Long save(SignalEvaluationRow evaluation, EntrySignalRow signal, Map<String, BigDecimal> trackStops) {
        evaluations.upsert(evaluation);
        if (signal == null) {
            return null;
        }
        EntrySignalRepository.Inserted inserted = signals.insertIfAbsent(signal);
        for (Map.Entry<String, BigDecimal> e : trackStops.entrySet()) {
            BigDecimal risk = signal.close().subtract(e.getValue());
            tracks.createIfAbsent(inserted.id(), e.getKey(), e.getValue(), signal.close().add(risk));
        }
        return inserted.created() ? inserted.id() : null;
    }
}
