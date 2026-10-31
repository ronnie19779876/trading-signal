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
    LocalDate date = tradeDate(k.getTime());
    BigDecimal turnover = k.hasTurnover() ? FutuNumbers.orNull(k.getTurnover()) : null;
    return new DailyBar(
        instrument,
        date,
        FutuNumbers.required(k.getOpenPrice(), 6, "open", instrument, date),
        FutuNumbers.required(k.getHighPrice(), 6, "high", instrument, date),
        FutuNumbers.required(k.getLowPrice(), 6, "low", instrument, date),
        FutuNumbers.required(k.getClosePrice(), 6, "close", instrument, date),
        k.hasLastClosePrice() ? FutuNumbers.orNull(k.getLastClosePrice(), 6) : null,
        k.getVolume(),
        turnover == null ? null : turnover.setScale(4, java.math.RoundingMode.HALF_UP),
        k.hasTurnoverRate() ? FutuNumbers.orNull(k.getTurnoverRate(), 6) : null,
        k.hasChangeRate() ? FutuNumbers.orNull(k.getChangeRate(), 6) : null,
        k.hasPe() ? FutuNumbers.orNull(k.getPe(), 6) : null,
        k.getIsBlank());
  }

  static LocalDate tradeDate(String time) {
    return LocalDate.parse(time.length() >= 10 ? time.substring(0, 10) : time);
  }
}
