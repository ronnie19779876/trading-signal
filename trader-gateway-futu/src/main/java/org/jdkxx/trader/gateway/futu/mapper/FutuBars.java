package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 富途 KLine → 领域日 K。富途 time 形如 "yyyy-MM-dd 00:00:00"（美股为美东日历日）。
 * 价格保留 6 位小数（富途美股 3 位），成交额 4 位。
 */
public final class FutuBars {

    private FutuBars() {
    }

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
                k.hasTurnover() ? BigDecimal.valueOf(k.getTurnover()).setScale(4, java.math.RoundingMode.HALF_UP) : null,
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
