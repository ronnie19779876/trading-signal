package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.RehabFactor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class FutuRehabs {

    private FutuRehabs() {
    }

    public static List<RehabFactor> toFactors(Instrument instrument, List<QotCommon.Rehab> rehabs) {
        List<RehabFactor> out = new ArrayList<>(rehabs.size());
        for (QotCommon.Rehab r : rehabs) {
            out.add(new RehabFactor(instrument, LocalDate.parse(r.getTime().substring(0, 10)),
                    factor(r.getFwdFactorA()), factor(r.getFwdFactorB()), factor(r.getBwdFactorA()), factor(r.getBwdFactorB()),
                    r.getCompanyActFlag(),
                    r.hasDividend() ? factor(r.getDividend()) : null,
                    r.hasSpDividend() ? factor(r.getSpDividend()) : null,
                    r.hasSplitBase() ? r.getSplitBase() : 0,
                    r.hasSplitErt() ? r.getSplitErt() : 0));
        }
        return out;
    }

    static BigDecimal factor(double v) {
        return BigDecimal.valueOf(v).setScale(10, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
    }
}
