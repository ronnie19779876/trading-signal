package org.jdkxx.trader.domain;

import java.time.LocalDate;

/**
 * Broker-side static information of an instrument. Futu answers {@code getStaticInfo} even for
 * codes it does not know ("unknown stock", brokerId=0), so {@code brokerId == 0} is the signal for
 * an unresolved symbol.
 */
public record InstrumentStatic(
    Instrument instrument,
    String name,
    SecurityType type,
    int lotSize,
    LocalDate listDate,
    boolean delisted,
    String exchange,
    long brokerId) {}
