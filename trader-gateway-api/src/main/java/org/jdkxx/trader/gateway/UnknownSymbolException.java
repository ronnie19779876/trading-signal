package org.jdkxx.trader.gateway;

import java.util.Set;
import org.jdkxx.trader.domain.Broker;

/**
 * The broker rejected a whole batch because it contains a symbol it does not recognize.
 *
 * <p>{@link #named()} holds the codes <b>the broker itself named</b>. They are only a hint and
 * <b>not sufficient grounds for removing anything</b>: measured on 2026-10-07 with {@code
 * snapshots([PSKY, NOSUCHXYZ, AAPL])}, Futu's retMsg only said "unknown stock PSKY" and never
 * mentioned the other bad code. Callers must therefore verify each instrument separately ({@code
 * UnknownSymbolGuard} decides by {@code staticInfo}'s brokerId==0).
 *
 * <p>Likewise, failing to extract any name (the broker changed its wording, or named nothing)
 * <b>does not affect recovery</b>: the verification does not depend on this text.
 */
public class UnknownSymbolException extends RequestRejectedException {

  private final Set<String> named;

  public UnknownSymbolException(Broker broker, int code, String message, Set<String> named) {
    super(broker, code, message);
    this.named = named == null ? Set.of() : Set.copyOf(named);
  }

  /** Codes named by the broker; may be empty and may be incomplete (see the class comment). */
  public Set<String> named() {
    return named;
  }
}
