package org.jdkxx.trader.domain.signal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conclusion of one gate. {@code criteria} is the criterion text with the numbers substituted in
 * and {@code values} holds the substituted values (fixed key order, may contain nulls, hence a
 * LinkedHashMap copy rather than {@code Map.copyOf}); both are persisted with the evaluation so
 * each gate can be re-checked.
 */
public record GateResult(Gate gate, Verdict verdict, String criteria, Map<String, Object> values) {

  public enum Gate {
    /** 1. Trend: whether to participate at all */
    TREND,
    /** 2. Location: the entry zone */
    LOCATION,
    /** 3. Trigger: the entry condition */
    TRIGGER,
    /** 4. Risk: stop distance */
    RISK
  }

  /** UNAVAILABLE = not enough data to decide, which <b>does not mean passed</b>. */
  public enum Verdict {
    PASS,
    FAIL,
    UNAVAILABLE
  }

  public GateResult {
    values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
  }

  public boolean passed() {
    return verdict == Verdict.PASS;
  }
}
