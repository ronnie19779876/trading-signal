package org.jdkxx.trader.domain;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Broker-side reference data of an instrument (contract details). {@code brokerRef} is the broker's
 * internal id (IBKR conId). {@code tradingHours} / {@code liquidHours} keep the broker's raw text;
 * parsing trading sessions was deferred to phase 2. {@code securityType} is the broker's security
 * type (IBKR stockType: COMMON / ADR / REIT / NY REG SHRS ...), which quant selection uses to
 * exclude ADRs and REITs (it is more reliable than Futu's type, which files some REITs as common
 * stock or Trust); null when absent.
 */
public record InstrumentInfo(
    Instrument instrument,
    Broker broker,
    String brokerRef,
    String name,
    String primaryExchange,
    String currency,
    BigDecimal minTick,
    ZoneId timeZone,
    String tradingHours,
    String liquidHours,
    String securityType) {

  public InstrumentInfo {
    Objects.requireNonNull(instrument, "instrument");
    Objects.requireNonNull(broker, "broker");
  }
}
