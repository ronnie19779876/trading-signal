package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetFinancialsEarningsPriceHistory;
import com.futu.openapi.pb.QotGetValuationDetail;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.EarningsTiming;
import org.jdkxx.trader.domain.selection.ValuationMetric;
import org.jdkxx.trader.domain.selection.ValuationPoint;
import org.jdkxx.trader.domain.selection.ValuationRange;

/**
 * Futu inputs for quant stock selection → domain (ARCHITECTURE §25.6). Semantics follow the
 * 2026-10-10 read-only probe:
 *
 * <ul>
 *   <li>valuation history has one entry per day, with date strings like {@code 2016-10-11}; the P/E
 *       of loss-making stocks is negative and is kept as is;
 *   <li>announcement dates cover only the latest 20 quarters and <b>include scheduled future
 *       dates</b>; they are not filtered here (the consumer decides relative to the evaluation
 *       date);
 *   <li>the time zone of the announcement time is unverified, so only the raw text is kept.
 * </ul>
 */
public final class FutuSelection {

  private FutuSelection() {}

  public static QotCommon.ValuationType valuationType(ValuationMetric metric) {
    return switch (metric) {
      case PE -> QotCommon.ValuationType.ValuationType_PE;
      case PB -> QotCommon.ValuationType.ValuationType_PB;
      case PS -> QotCommon.ValuationType.ValuationType_PS;
    };
  }

  public static QotCommon.ValuationIntervalType interval(ValuationRange range) {
    return switch (range) {
      case MONTH3 -> QotCommon.ValuationIntervalType.ValuationIntervalType_Month3;
      case YEAR10 -> QotCommon.ValuationIntervalType.ValuationIntervalType_Year10;
    };
  }

  /**
   * Points without a date or value (or with a non-finite value) are skipped; sorted from earliest
   * to latest, keeping the last entry for a given day.
   */
  public static List<ValuationPoint> toValuationPoints(QotGetValuationDetail.S2C s2c) {
    List<ValuationPoint> out = new ArrayList<>();
    if (!s2c.hasTrend()) {
      return out;
    }
    for (QotGetValuationDetail.ValuationTrend.ValuationHistoricalItem i :
        s2c.getTrend().getHistoricalItemsList()) {
      LocalDate d = date(i.getTimeStr());
      if (d == null || !i.hasValue() || !Double.isFinite(i.getValue())) {
        continue;
      }
      out.add(new ValuationPoint(d, BigDecimal.valueOf(i.getValue())));
    }
    out.sort(Comparator.comparing(ValuationPoint::tradeDate));
    List<ValuationPoint> dedup = new ArrayList<>(out.size());
    for (ValuationPoint p : out) {
      if (!dedup.isEmpty() && dedup.getLast().tradeDate().equals(p.tradeDate())) {
        dedup.removeLast();
      }
      dedup.add(p);
    }
    return dedup;
  }

  /**
   * Entries without a period label or announcement date are skipped (they cannot be matched to a
   * report).
   */
  public static List<EarningsEvent> toEarningsEvents(QotGetFinancialsEarningsPriceHistory.S2C s2c) {
    List<EarningsEvent> out = new ArrayList<>();
    for (QotGetFinancialsEarningsPriceHistory.PriceHistoryOnEarningsDays d :
        s2c.getDetailListList()) {
      LocalDate pub = date(d.getPubTradingDayStr());
      String period = d.getPeriodText();
      if (pub == null || period == null || period.isBlank()) {
        continue;
      }
      String time = d.getPubTimeStr();
      out.add(
          new EarningsEvent(
              period,
              d.getFiscalYear(),
              pub,
              time == null || time.isBlank() ? null : time,
              timing(d.getPubType()),
              d.getIsCurrent()));
    }
    return out;
  }

  static EarningsTiming timing(QotCommon.EarningsPubTimeType t) {
    return switch (t) {
      case EarningsPubTimeType_PreMarket -> EarningsTiming.PRE_MARKET;
      case EarningsPubTimeType_AfterMarket -> EarningsTiming.AFTER_MARKET;
      case EarningsPubTimeType_DuringMarket -> EarningsTiming.DURING_MARKET;
      default -> EarningsTiming.UNKNOWN;
    };
  }

  static LocalDate date(String s) {
    if (s == null || s.length() < 10) {
      return null;
    }
    try {
      return LocalDate.parse(s.substring(0, 10));
    } catch (RuntimeException e) {
      return null;
    }
  }
}
