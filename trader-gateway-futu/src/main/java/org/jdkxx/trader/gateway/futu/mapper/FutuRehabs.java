package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.RehabFactor;

/**
 * Futu price-adjustment records → domain factors, one record per corporate-action event. The
 * factors are per-event ratios, not cumulative values; compounding (forward adjustment from early
 * to late, backward from late to early) happens in the read layer. Split / consolidation / bonus /
 * transfer base and ratio are kept as given (0 when absent) because signal evaluation uses the
 * exact base/ert share ratios for pure share-count events (fwdFactorA keeps only 5 decimals) and
 * fwdFactorA only for the price of mixed events (ARCHITECTURE §18.4).
 */
public final class FutuRehabs {

  private FutuRehabs() {}

  public static List<RehabFactor> toFactors(Instrument instrument, List<QotCommon.Rehab> rehabs) {
    List<RehabFactor> out = new ArrayList<>(rehabs.size());
    for (QotCommon.Rehab r : rehabs) {
      out.add(
          new RehabFactor(
              instrument,
              LocalDate.parse(r.getTime().substring(0, 10)),
              factor(r.getFwdFactorA()),
              factor(r.getFwdFactorB()),
              factor(r.getBwdFactorA()),
              factor(r.getBwdFactorB()),
              r.getCompanyActFlag(),
              r.hasDividend() ? factor(r.getDividend()) : null,
              r.hasSpDividend() ? factor(r.getSpDividend()) : null,
              r.hasSplitBase() ? r.getSplitBase() : 0,
              r.hasSplitErt() ? r.getSplitErt() : 0,
              r.hasJoinBase() ? r.getJoinBase() : 0,
              r.hasJoinErt() ? r.getJoinErt() : 0,
              r.hasBonusBase() ? r.getBonusBase() : 0,
              r.hasBonusErt() ? r.getBonusErt() : 0,
              r.hasTransferBase() ? r.getTransferBase() : 0,
              r.hasTransferErt() ? r.getTransferErt() : 0));
    }
    return out;
  }

  static BigDecimal factor(double v) {
    return BigDecimal.valueOf(v).setScale(10, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
  }
}
