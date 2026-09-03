package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import org.jdkxx.trader.domain.MarketSession;
import org.jdkxx.trader.domain.Quote;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class FutuQuotesTest {

    /** 实测盘前样本：curPrice 冻结在昨收 357.01，preMarket 359.89 / +2.88。 */
    private static QotCommon.BasicQot preMarketSample() {
        return QotCommon.BasicQot.newBuilder()
                .setSecurity(QotCommon.Security.newBuilder().setMarket(11).setCode("TSLA"))
                .setIsSuspended(false).setListTime("2010-06-29").setPriceSpread(0.01).setUpdateTime("2026-09-02 16:00:00.231")
                .setHighPrice(360).setOpenPrice(350).setLowPrice(348).setCurPrice(357.01).setLastClosePrice(355.0)
                .setVolume(33951909L).setTurnover(1.2E10).setTurnoverRate(0.01).setAmplitude(3.3)
                .setPreMarket(QotCommon.PreAfterMarketData.newBuilder().setPrice(359.89).setChangeVal(2.88).setChangeRate(0.8067).setVolume(172248))
                .setAfterMarket(QotCommon.PreAfterMarketData.newBuilder().setPrice(356.85).setChangeVal(-0.16).setChangeRate(-0.0448).setVolume(500000))
                .build();
    }

    @Test
    void 盘前取preMarket为有效价_常规时段取curPrice() {
        Quote pre = FutuQuotes.toQuote(preMarketSample(), MarketSession.PRE, Instant.EPOCH);
        assertThat(pre.price()).isEqualByComparingTo("359.89");
        assertThat(pre.change()).isEqualByComparingTo("2.88");
        assertThat(pre.rthPrice()).isEqualByComparingTo("357.01");
        assertThat(pre.preMarket().volume()).isEqualTo(172248);

        Quote rth = FutuQuotes.toQuote(preMarketSample(), MarketSession.RTH, Instant.EPOCH);
        assertThat(rth.price()).isEqualByComparingTo("357.01");
        assertThat(rth.change()).isEqualByComparingTo("2.01");          // 357.01 − 355
        assertThat(rth.changeRate()).isEqualByComparingTo("0.5662");     // 2.01 / 355 × 100

        Quote after = FutuQuotes.toQuote(preMarketSample(), MarketSession.AFTER, Instant.EPOCH);
        assertThat(after.price()).isEqualByComparingTo("356.85");
        assertThat(after.instrument().symbol()).isEqualTo("TSLA");
        assertThat(after.quoteTime()).isNotNull();
    }

    @Test
    void 时段判定优先市场状态其次美东时钟() {
        ZonedDateTime et = ZonedDateTime.of(2026, 9, 3, 5, 58, 0, 0, ZoneId.of("America/New_York"));
        assertThat(FutuQuotes.session("PreMarketBegin", et)).isEqualTo(MarketSession.PRE);
        assertThat(FutuQuotes.session("Afternoon", et)).isEqualTo(MarketSession.RTH);
        assertThat(FutuQuotes.session("AfterHoursBegin", et)).isEqualTo(MarketSession.AFTER);
        assertThat(FutuQuotes.session("AfterHoursEnd", et)).isEqualTo(MarketSession.CLOSED);
        assertThat(FutuQuotes.session("NightOpen", et)).isEqualTo(MarketSession.OVERNIGHT);
        assertThat(FutuQuotes.session(null, et)).isEqualTo(MarketSession.PRE);
        assertThat(FutuQuotes.session("Whatever", et.withHour(10))).isEqualTo(MarketSession.RTH);
        assertThat(FutuQuotes.session(null, et.withHour(17))).isEqualTo(MarketSession.AFTER);
        assertThat(FutuQuotes.session(null, et.withHour(22))).isEqualTo(MarketSession.CLOSED);
        assertThat(FutuQuotes.session(null, et.plusDays(2).withHour(10))).isEqualTo(MarketSession.CLOSED);   // 周六
    }
}
