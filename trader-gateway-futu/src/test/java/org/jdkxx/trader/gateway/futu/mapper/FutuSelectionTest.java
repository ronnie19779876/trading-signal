package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetFinancialsEarningsPriceHistory;
import com.futu.openapi.pb.QotGetValuationDetail;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.EarningsTiming;
import org.jdkxx.trader.domain.selection.ValuationPoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FutuSelectionTest {

    private static QotGetValuationDetail.ValuationTrend.ValuationHistoricalItem item(String time, double v) {
        return QotGetValuationDetail.ValuationTrend.ValuationHistoricalItem.newBuilder().setTimeStr(time).setValue(v).buildPartial();
    }

    @Test
    void 估值历史按日期排序_同日留最后一条_负值保留() {
        QotGetValuationDetail.S2C s2c = QotGetValuationDetail.S2C.newBuilder()
                .setTrend(QotGetValuationDetail.ValuationTrend.newBuilder()
                        .addHistoricalItems(item("2016-10-12", 12.645))
                        .addHistoricalItems(item("2016-10-11", 12.533))
                        .addHistoricalItems(item("2023-05-03", -43.785))
                        .addHistoricalItems(item("2016-10-12", 12.7))
                        .addHistoricalItems(item("", 1))
                        .buildPartial())
                .buildPartial();

        List<ValuationPoint> out = FutuSelection.toValuationPoints(s2c);

        assertThat(out).extracting(ValuationPoint::tradeDate).containsExactly(
                LocalDate.of(2016, 10, 11), LocalDate.of(2016, 10, 12), LocalDate.of(2023, 5, 3));
        assertThat(out.get(1).value()).isEqualByComparingTo("12.7");
        assertThat(out.get(2).value()).isEqualByComparingTo("-43.785");
    }

    @Test
    void 没有趋势段时回空() {
        assertThat(FutuSelection.toValuationPoints(QotGetValuationDetail.S2C.newBuilder().buildPartial())).isEmpty();
    }

    @Test
    void 公告日映射_未来预定日期也保留_缺期别或日期的跳过() {
        QotGetFinancialsEarningsPriceHistory.S2C s2c = QotGetFinancialsEarningsPriceHistory.S2C.newBuilder()
                .addDetailList(QotGetFinancialsEarningsPriceHistory.PriceHistoryOnEarningsDays.newBuilder()
                        .setPeriodText("2026/Q4").setFiscalYear(2026).setPubTradingDayStr("2026-11-02")
                        .setPubTimeStr("2026-11-02 16:00:00").setPubType(QotCommon.EarningsPubTimeType.EarningsPubTimeType_AfterMarket)
                        .setIsCurrent(false).buildPartial())
                .addDetailList(QotGetFinancialsEarningsPriceHistory.PriceHistoryOnEarningsDays.newBuilder()
                        .setPeriodText("2026/Q3").setFiscalYear(2026).setPubTradingDayStr("2026-10-13")
                        .setPubType(QotCommon.EarningsPubTimeType.EarningsPubTimeType_PreMarket).setIsCurrent(true).buildPartial())
                .addDetailList(QotGetFinancialsEarningsPriceHistory.PriceHistoryOnEarningsDays.newBuilder()
                        .setPeriodText("2026/Q2").buildPartial())
                .buildPartial();

        List<EarningsEvent> out = FutuSelection.toEarningsEvents(s2c);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).pubDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(out.get(0).timing()).isEqualTo(EarningsTiming.AFTER_MARKET);
        assertThat(out.get(0).pubTimeText()).isEqualTo("2026-11-02 16:00:00");
        assertThat(out.get(1).timing()).isEqualTo(EarningsTiming.PRE_MARKET);
        assertThat(out.get(1).current()).isTrue();
        assertThat(out.get(1).pubTimeText()).isNull();
    }
}
