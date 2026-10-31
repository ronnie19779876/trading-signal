package org.jdkxx.trader.storage.signal;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one instrument's evaluation for one day as a unit: the evaluation row, the signal (if any)
 * and the paper ledger rows of both exit variants go into the same transaction, so there is never a
 * half state such as "signal without ledger" or "evaluation without signal".
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SignalStore {

  private final SignalEvaluationRepository evaluations;
  private final EntrySignalRepository signals;
  private final SignalTrackRepository tracks;

  public SignalStore(
      SignalEvaluationRepository evaluations,
      EntrySignalRepository signals,
      SignalTrackRepository tracks) {
    this.evaluations = evaluations;
    this.signals = signals;
    this.tracks = tracks;
  }

  /**
   * On a rerun the evaluation row is overwritten, but an existing signal and its ledger rows are
   * kept as they are (insert-if-absent), so a signal already published is never duplicated or
   * silently rewritten.
   *
   * <p>Each variant's +1R level is close + (close − stop), i.e. one risk unit above the
   * decision-day close, in decision-day prices.
   *
   * @param signal null means the day is not a signal
   * @param trackStops variant → that variant's stop (decision-day prices); ignored when signal is
   *     null
   * @return the id of the newly created signal; null when there is no signal or the signal already
   *     existed (rerun)
   */
  @Transactional
  public Long save(
      SignalEvaluationRow evaluation, EntrySignalRow signal, Map<String, BigDecimal> trackStops) {
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
