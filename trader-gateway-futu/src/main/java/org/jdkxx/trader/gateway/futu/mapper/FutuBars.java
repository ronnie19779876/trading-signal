package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;

/**
 * Futu KLine → domain daily bar. Futu's time looks like "yyyy-MM-dd 00:00:00" (for US stocks a
 * US/Eastern calendar date). Prices keep 6 decimal places (Futu gives 3 for US stocks), turnover 4.
 */
public final class FutuBars {

  private FutuBars() {}

  public static List<DailyBar> toDailyBars(Instrument instrument, List<QotCommon.KLine> lines) {
    List<DailyBar> out = new ArrayList<>(lines.size());
    for (QotCommon.KLine k : lines) {
      out.add(toDailyBar(instrument, k));
    }
    return out;
  }

  public static DailyBar toDailyBar(Instrument instrument, QotCommon.KLine k) {
    return new DailyBar(
        instrument,
        tradeDate(k.getTime()),
        price(k.getOpenPrice()),
        price(k.getHighPrice()),
        price(k.getLowPrice()),
        price(k.getClosePrice()),
        k.hasLastClosePrice() ? price(k.getLastClosePrice()) : null,
        k.getVolume(),
        k.hasTurnover()
            ? BigDecimal.valueOf(k.getTurnover()).setScale(4, java.math.RoundingMode.HALF_UP)
            : null,
        k.hasTurnoverRate() ? rate(k.getTurnoverRate()) : null,
        k.hasChangeRate() ? rate(k.getChangeRate()) : null,
        k.hasPe() ? rate(k.getPe()) : null,
        k.getIsBlank());
  }

  static LocalDate tradeDate(String time) {
    return LocalDate.parse(time.length() >= 10 ? time.substring(0, 10) : time);
  }

  static BigDecimal price(double v) {
    return BigDecimal.valueOf(v).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
  }

  static BigDecimal rate(double v) {
    return BigDecimal.valueOf(v).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
  }
}
