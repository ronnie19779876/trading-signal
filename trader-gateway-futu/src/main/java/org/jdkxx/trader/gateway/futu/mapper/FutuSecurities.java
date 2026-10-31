package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;

/**
 * Domain instrument → Futu market-data security identifier. In the Java SDK the code carries no
 * market prefix ("AAPL", "00700"); the market is expressed by an enum.
 */
public final class FutuSecurities {

  private FutuSecurities() {}

  public static QotCommon.Security of(Instrument instrument) {
    return QotCommon.Security.newBuilder()
        .setMarket(market(instrument.market()))
        .setCode(instrument.symbol())
        .build();
  }

  public static Market market(int qotMarket) {
    return qotMarket == QotCommon.QotMarket.QotMarket_HK_Security_VALUE ? Market.HK : Market.US;
  }

  public static int market(Market market) {
    return switch (market) {
      case US -> QotCommon.QotMarket.QotMarket_US_Security_VALUE;
      case HK -> QotCommon.QotMarket.QotMarket_HK_Security_VALUE;
    };
  }
}
