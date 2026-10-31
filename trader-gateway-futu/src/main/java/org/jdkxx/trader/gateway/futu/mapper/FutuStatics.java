package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;

/**
 * Futu static info → domain. secType: 3 common stock, 4 ETF (QotCommon.SecurityType). Futu also
 * classifies US REITs as Trust, so they come out as ETF here; the authoritative security type comes
 * from IBKR's stockType. For a symbol Futu does not know, it still returns an entry ("unknown
 * stock", id 0), which is passed on as brokerId 0 so that the caller can mark it UNRESOLVED.
 */
public final class FutuStatics {

  private FutuStatics() {}

  public static List<InstrumentStatic> toStatics(List<QotCommon.SecurityStaticInfo> infos) {
    List<InstrumentStatic> out = new ArrayList<>(infos.size());
    for (QotCommon.SecurityStaticInfo i : infos) {
      QotCommon.SecurityStaticBasic b = i.getBasic();
      out.add(
          new InstrumentStatic(
              new Instrument(market(b.getSecurity().getMarket()), b.getSecurity().getCode()),
              b.getName(),
              type(b.getSecType()),
              b.getLotSize(),
              listDate(b.getListTime()),
              b.getDelisting(),
              b.hasExchType() ? Integer.toString(b.getExchType()) : null,
              b.getId()));
    }
    return out;
  }

  static Market market(int qotMarket) {
    return qotMarket == QotCommon.QotMarket.QotMarket_HK_Security_VALUE ? Market.HK : Market.US;
  }

  static SecurityType type(int secType) {
    if (secType == QotCommon.SecurityType.SecurityType_Eqty_VALUE) {
      return SecurityType.STOCK;
    }
    if (secType == QotCommon.SecurityType.SecurityType_Trust_VALUE) {
      return SecurityType.ETF;
    }
    return SecurityType.OTHER;
  }

  static LocalDate listDate(String s) {
    if (s == null || s.length() < 10) {
      return null;
    }
    try {
      LocalDate d = LocalDate.parse(s.substring(0, 10));
      return d.getYear() <= 1970 ? null : d; // 1970-01-01 means "unknown"
    } catch (RuntimeException e) {
      return null;
    }
  }
}
