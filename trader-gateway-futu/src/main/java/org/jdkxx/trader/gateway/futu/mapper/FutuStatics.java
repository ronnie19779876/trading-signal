package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 富途静态信息 → 领域。secType：3 正股、4 ETF（QotCommon.SecurityType）。 */
public final class FutuStatics {

    private FutuStatics() {
    }

    public static List<InstrumentStatic> toStatics(List<QotCommon.SecurityStaticInfo> infos) {
        List<InstrumentStatic> out = new ArrayList<>(infos.size());
        for (QotCommon.SecurityStaticInfo i : infos) {
            QotCommon.SecurityStaticBasic b = i.getBasic();
            out.add(new InstrumentStatic(
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
            return d.getYear() <= 1970 ? null : d;   // 1970-01-01 是"未知"
        } catch (RuntimeException e) {
            return null;
        }
    }
}
