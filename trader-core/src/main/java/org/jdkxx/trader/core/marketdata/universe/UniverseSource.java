package org.jdkxx.trader.core.marketdata.universe;

import java.util.List;
import org.jdkxx.trader.domain.IndexCode;

/**
 * A source of index constituents. {@link #fetch} must throw rather than return a short list when it
 * cannot read the source properly; a short list is applied as a set difference (guarded by the
 * exit-count limit).
 */
public interface UniverseSource {

  String name();

  List<ConstituentEntry> fetch(IndexCode index);
}
