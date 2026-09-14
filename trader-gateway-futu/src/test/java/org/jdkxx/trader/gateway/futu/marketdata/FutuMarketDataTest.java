package org.jdkxx.trader.gateway.futu.marketdata;

import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetKL;
import com.futu.openapi.pb.QotRequestHistoryKL;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.gateway.futu.QotCalls;
import org.jdkxx.trader.gateway.futu.mapper.FutuSecurities;
import org.jdkxx.trader.shaded.futu.protobuf.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FutuMarketDataTest {

    private static final Instrument AAPL = Instrument.us("AAPL");

    private final ExecutorService dispatch = Executors.newSingleThreadExecutor(r -> new Thread(r, "futu-dispatch"));
    private final ExecutorService pager = Executors.newSingleThreadExecutor(r -> new Thread(r, "futu-pager"));

    @AfterEach
    void tearDown() {
        dispatch.shutdownNow();
        pager.shutdownNow();
    }

    private static QotCommon.KLine kline(String day) {
        return QotCommon.KLine.newBuilder().setTime(day + " 00:00:00").setIsBlank(false)
                .setOpenPrice(1).setHighPrice(1).setLowPrice(1).setClosePrice(1).setLastClosePrice(1)
                .setVolume(1L).setTurnover(1).setTurnoverRate(0).setPe(0).setChangeRate(0)
                .buildPartial();
    }

    /** 回复一律在 futu-dispatch 上完成，与真实注册表一致。 */
    private <R> CompletableFuture<R> replyOnDispatch(Class<R> type, Object rsp) {
        CompletableFuture<R> f = new CompletableFuture<>();
        dispatch.execute(() -> f.complete(type.cast(rsp)));
        return f;
    }

    @Test
    void 历史K线续页不在回复线程上发() throws Exception {
        List<String> senders = new CopyOnWriteArrayList<>();
        AtomicInteger page = new AtomicInteger();
        // 回复由测试在续接挂上之后才交给 dispatch 完成：回复若先于续接完成，续接会跑在挂接它的线程上，测不出区别
        BlockingQueue<Runnable> replies = new LinkedBlockingQueue<>();
        QotCalls calls = new QotCalls() {
            @Override
            public <R> CompletableFuture<R> call(String limitName, String what, Class<R> type, ToIntFunction<FTAPI_Conn_Qot> send) {
                senders.add(Thread.currentThread().getName());
                int n = page.getAndIncrement();
                QotRequestHistoryKL.S2C.Builder s2c = QotRequestHistoryKL.S2C.newBuilder()
                        .setSecurity(FutuSecurities.of(AAPL))
                        .addKlList(kline(n == 0 ? "2026-09-10" : "2026-09-11"));
                if (n == 0) {
                    s2c.setNextReqKey(ByteString.copyFromUtf8("next"));
                }
                Object rsp = QotRequestHistoryKL.Response.newBuilder().setRetType(0).setS2C(s2c.buildPartial()).buildPartial();
                CompletableFuture<R> f = new CompletableFuture<>();
                replies.add(() -> f.complete(type.cast(rsp)));
                return f;
            }
        };

        CompletableFuture<List<DailyBar>> result = new FutuMarketData(calls, pager)
                .historyDailyBars(AAPL, LocalDate.of(2006, 1, 1), LocalDate.of(2026, 9, 11));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!result.isDone() && System.nanoTime() < deadline) {
            Runnable reply = replies.poll(50, TimeUnit.MILLISECONDS);
            if (reply != null) {
                dispatch.execute(reply);
            }
        }
        List<DailyBar> bars = result.get(1, TimeUnit.SECONDS);

        assertThat(bars).extracting(DailyBar::tradeDate).containsExactly(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11));
        assertThat(senders).hasSize(2);
        assertThat(senders.get(1)).as("续页要先过限流器、可能阻塞，不能占着回复线程").isNotEqualTo("futu-dispatch");
    }

    @Test
    void 回复的标的与请求不一致时失败_不把别人的K线记到这只名下() {
        QotCalls calls = new QotCalls() {
            @Override
            public <R> CompletableFuture<R> call(String limitName, String what, Class<R> type, ToIntFunction<FTAPI_Conn_Qot> send) {
                QotGetKL.S2C s2c = QotGetKL.S2C.newBuilder().setSecurity(FutuSecurities.of(Instrument.us("MSFT")))
                        .addKlList(kline("2026-09-11")).buildPartial();
                return replyOnDispatch(type, QotGetKL.Response.newBuilder().setRetType(0).setS2C(s2c).buildPartial());
            }
        };

        CompletableFuture<List<DailyBar>> f = new FutuMarketData(calls, pager).recentDailyBars(AAPL, 5);

        assertThatThrownBy(() -> f.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    void 代码大小写不同不算不一致() {
        QotCommon.Security got = QotCommon.Security.newBuilder(FutuSecurities.of(AAPL)).setCode("aapl").build();

        FutuMarketData.requireSameSecurity(AAPL, true, got, "getKL");
        FutuMarketData.requireSameSecurity(AAPL, false, QotCommon.Security.getDefaultInstance(), "getKL");
    }
}
