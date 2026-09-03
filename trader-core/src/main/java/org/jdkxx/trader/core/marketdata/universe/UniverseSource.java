package org.jdkxx.trader.core.marketdata.universe;

import org.jdkxx.trader.domain.IndexCode;

import java.util.List;

public interface UniverseSource {

    String name();

    List<ConstituentEntry> fetch(IndexCode index);
}
