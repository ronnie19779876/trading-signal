package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetFinancialsEarningsPriceHistory;
import com.futu.openapi.pb.QotGetValuationDetail;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.EarningsTiming;
import org.jdkxx.trader.domain.selection.ValuationMetric;
import org.jdkxx.trader.domain.selection.ValuationPoint;
import org.jdkxx.trader.domain.selection.ValuationRange;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 量化选股的富途输入 → 领域（ARCHITECTURE §25.6）。口径以 2026-10-10 只读探针为准：
 * <ul>
 *   <li>估值历史逐日一条，日期串形如 {@code 2016-10-11}；亏损股 PE 为负，原样保留；</li>
 *   <li>公告日只回最近 20 季，<b>含未来的预定日期</b>，不在这里过滤（由使用方按判定日取舍）；</li>
 *   <li>公告时刻的时区未经证实，只留原文。</li>
 * </ul>
 */
public final class FutuSelection {

    private FutuSelection() {
    }

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

    /** 没有日期或没有值的点跳过；按日期从早到晚，同一天保留最后一条。 */
    public static List<ValuationPoint> toValuationPoints(QotGetValuationDetail.S2C s2c) {
        List<ValuationPoint> out = new ArrayList<>();
        if (!s2c.hasTrend()) {
            return out;
        }
        for (QotGetValuationDetail.ValuationTrend.ValuationHistoricalItem i : s2c.getTrend().getHistoricalItemsList()) {
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

    /** 没有期别或公告日的条目跳过（没法与财报对上）。 */
    public static List<EarningsEvent> toEarningsEvents(QotGetFinancialsEarningsPriceHistory.S2C s2c) {
        List<EarningsEvent> out = new ArrayList<>();
        for (QotGetFinancialsEarningsPriceHistory.PriceHistoryOnEarningsDays d : s2c.getDetailListList()) {
            LocalDate pub = date(d.getPubTradingDayStr());
            String period = d.getPeriodText();
            if (pub == null || period == null || period.isBlank()) {
                continue;
            }
            String time = d.getPubTimeStr();
            out.add(new EarningsEvent(period, d.getFiscalYear(), pub, time == null || time.isBlank() ? null : time,
                    timing(d.getPubType()), d.getIsCurrent()));
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
