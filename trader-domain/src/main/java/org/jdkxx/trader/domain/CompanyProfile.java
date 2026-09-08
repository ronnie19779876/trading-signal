package org.jdkxx.trader.domain;

import java.util.Map;

/** 公司简介。券商给的字段零散且随市场不同，按原样保留键值，交由上层决定怎么展示。 */
public record CompanyProfile(Instrument instrument, Map<String, String> fields) {
}
