package org.jdkxx.trader.domain;

import java.util.Map;

/**
 * Company profile. The broker's fields are scattered and differ by market, so the key/value pairs
 * are kept as received and the upper layers decide how to display them.
 */
public record CompanyProfile(Instrument instrument, Map<String, String> fields) {}
