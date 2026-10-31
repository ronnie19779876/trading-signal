package org.jdkxx.trader.domain.signal;

import java.time.LocalDate;
import java.util.List;

/**
 * Price zone clustered from fractal points: support zones come from fractal lows, resistance zones
 * from fractal highs. The zone width is not normalized; it is itself a measure of how densely the
 * price level was retested.
 *
 * @param touches number of member fractal points (number of touches)
 * @param members dates of the member fractal points, ascending
 */
public record PriceZone(double bottom, double top, int touches, List<LocalDate> members) {

  public PriceZone {
    members = List.copyOf(members);
  }

  public boolean contains(double price, double tolerance) {
    return price >= bottom - tolerance && price <= top + tolerance;
  }
}
