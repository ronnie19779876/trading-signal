package org.jdkxx.trader.domain;

import java.time.LocalDate;

/** 券商侧的标的静态信息。 */
public record InstrumentStatic(Instrument instrument, String name, SecurityType type, int lotSize, LocalDate listDate,
                               boolean delisted, String exchange, long brokerId) {
}
