package org.jdkxx.trader.core.marketdata.universe;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.RequestTimeoutException;
import org.jdkxx.trader.gateway.UnknownSymbolException;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批次级恢复的判定与三道守护。
 *
 * <p>这些断言对应的实测结论（2026-10-07 对真实网关）：
 * {@code staticInfo} 混批免疫、回 brokerId=0；{@code snapshots} 混批整批失败；
 * retMsg 只点名一只（哪怕批里有两只坏代码）。
 */
class UnknownSymbolGuardTest {

    private static final int MAX_UNKNOWN = 3;

    private final MarketDataGateway gateway = mock(MarketDataGateway.class);
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final UnknownSymbolGuard guard = new UnknownSymbolGuard(gateway, instruments, 200, MAX_UNKNOWN);

    @Test
    void 混批里一只不认识时只剔除它并标成UNRESOLVED() {
        known("AAPL", "MSFT");
        unknown("PSKY");
        row("PSKY", 358, "RESOLVED");

        Optional<UnknownSymbolGuard.Outcome> out = guard.inspect(us("PSKY", "AAPL", "MSFT"), rejected("未知股票 PSKY"), true);

        assertThat(out).isPresent();
        assertThat(out.get().exclude()).containsExactly("PSKY");
        assertThat(out.get().marked()).containsExactly("PSKY");
        assertThat(out.get().canRetry()).isTrue();
        verify(instruments).markUnresolved(358L);
    }

    /** 实测：retMsg 只报一只，另一只坏代码根本没被提及——照文本剔除会漏掉它，下一批还是会被毒。 */
    @Test
    void 券商只点名一只时仍按逐只核实找全两只() {
        known("AAPL");
        unknown("PSKY", "NOSUCHXYZ");
        row("PSKY", 358, "RESOLVED");
        row("NOSUCHXYZ", 999, "RESOLVED");

        UnknownSymbolGuard.Outcome out = guard.inspect(us("PSKY", "NOSUCHXYZ", "AAPL"),
                rejected("未知股票 PSKY"), true).orElseThrow();

        assertThat(out.exclude()).containsExactlyInAnyOrder("PSKY", "NOSUCHXYZ");
        assertThat(out.marked()).containsExactlyInAnyOrder("PSKY", "NOSUCHXYZ");
        assertThat(out.detail()).contains("它只点名了 [PSKY]");
    }

    /** 守护一：超时、断连、普通异常都不是"券商明确拒绝"，一律不介入——否则一次网关抖动就集体降级。 */
    @Test
    void 守护一_超时与断连不触发() {
        assertThat(guard.inspect(us("PSKY", "AAPL"), new ExecutionException(
                new RequestTimeoutException(Broker.FUTU, "getSecuritySnapshot")), true)).isEmpty();
        assertThat(guard.inspect(us("PSKY", "AAPL"), new ExecutionException(
                new NotConnectedException(Broker.FUTU, "行情通道未连接")), true)).isEmpty();
        assertThat(guard.inspect(us("PSKY", "AAPL"), new IllegalStateException("随便一个异常"), true)).isEmpty();

        verify(instruments, never()).markUnresolved(anyLong());
    }

    /** 守护二：坏得太多更像富途侧出了事，整批不动也不写库。 */
    @Test
    void 守护二_超过阈值时整批不动也不标记() {
        known("AAPL");
        unknown("S1", "S2", "S3", "S4");
        row("S1", 1, "RESOLVED");

        UnknownSymbolGuard.Outcome out = guard.inspect(us("S1", "S2", "S3", "S4", "AAPL"),
                rejected("未知股票 S1"), true).orElseThrow();

        assertThat(out.canRetry()).isFalse();
        assertThat(out.exclude()).isEmpty();
        assertThat(out.marked()).isEmpty();
        assertThat(out.detail()).contains("超过阈值 " + MAX_UNKNOWN).contains("整批不动");
        verify(instruments, never()).markUnresolved(anyLong());
    }

    @Test
    void 刚好等于阈值时照常剔除() {
        known("AAPL");
        unknown("S1", "S2", "S3");
        row("S1", 1, "RESOLVED");
        row("S2", 2, "RESOLVED");
        row("S3", 3, "RESOLVED");

        UnknownSymbolGuard.Outcome out = guard.inspect(us("S1", "S2", "S3", "AAPL"),
                rejected("未知股票 S1"), true).orElseThrow();

        assertThat(out.exclude()).hasSize(MAX_UNKNOWN);
        assertThat(out.canRetry()).isTrue();
    }

    /** 核实结果与券商说法不一致时信核实：富途拒绝了，但逐只问都认识——不按未知代码处理，更不写库。 */
    @Test
    void 逐只核实都认识时不剔除也不标记() {
        known("AAPL", "MSFT");

        UnknownSymbolGuard.Outcome out = guard.inspect(us("AAPL", "MSFT"),
                rejected("别的什么错误"), true).orElseThrow();

        assertThat(out.canRetry()).isFalse();
        assertThat(out.detail()).contains("逐只核实都认识");
        verify(instruments, never()).markUnresolved(anyLong());
    }

    /** 没有判据就不写库：核实本身失败时什么都不做。 */
    @Test
    void 核实失败时不写库() {
        when(gateway.staticInfo(anyList())).thenReturn(CompletableFuture.failedFuture(
                new RequestTimeoutException(Broker.FUTU, "getStaticInfo")));

        UnknownSymbolGuard.Outcome out = guard.inspect(us("PSKY", "AAPL"), rejected("未知股票 PSKY"), true).orElseThrow();

        assertThat(out.canRetry()).isFalse();
        assertThat(out.detail()).contains("核实未知代码失败");
        verify(instruments, never()).markUnresolved(anyLong());
    }

    /** 决策 2：持仓与实时订阅侧只剔除、不写库——我们真正持有的标的改名值得人看一眼。 */
    @Test
    void 不允许写库时剔除照做但不标记() {
        known("AAPL");
        unknown("PSKY");
        row("PSKY", 358, "RESOLVED");

        UnknownSymbolGuard.Outcome out = guard.inspect(us("PSKY", "AAPL"), rejected("未知股票 PSKY"), false).orElseThrow();

        assertThat(out.exclude()).containsExactly("PSKY");
        assertThat(out.marked()).isEmpty();
        assertThat(out.detail()).contains("不写库");
        verify(instruments, never()).markUnresolved(anyLong());
    }

    /** 反订阅一只已经不认识的代码会永久失败，把它从本地账本摘掉正是修法——所以一只也要介入。 */
    @Test
    void 只有一只时也介入() {
        unknown("PSKY");
        row("PSKY", 358, "UNRESOLVED");

        UnknownSymbolGuard.Outcome out = guard.inspect(us("PSKY"), rejected("未知股票 PSKY"), false).orElseThrow();

        assertThat(out.exclude()).containsExactly("PSKY");
        assertThat(out.canRetry()).isTrue();
    }

    /** 已经是 UNRESOLVED 的不重复写库。 */
    @Test
    void 已经是UNRESOLVED的不重复标记() {
        known("AAPL");
        unknown("PSKY");
        row("PSKY", 358, "UNRESOLVED");

        UnknownSymbolGuard.Outcome out = guard.inspect(us("PSKY", "AAPL"), rejected("未知股票 PSKY"), true).orElseThrow();

        assertThat(out.exclude()).containsExactly("PSKY");
        assertThat(out.marked()).isEmpty();
        verify(instruments, never()).markUnresolved(anyLong());
    }

    /** 请求了却没在应答里出现的，也算富途不认识（与 resolveStatics 同一口径）。 */
    @Test
    void 应答里缺的代码也算不认识() {
        when(gateway.staticInfo(anyList())).thenReturn(CompletableFuture.completedFuture(
                List.of(statics("AAPL", 205189L))));
        row("PSKY", 358, "RESOLVED");

        UnknownSymbolGuard.Outcome out = guard.inspect(us("PSKY", "AAPL"), rejected("未知股票 PSKY"), true).orElseThrow();

        assertThat(out.exclude()).containsExactly("PSKY");
        verify(instruments).markUnresolved(358L);
    }

    // ------------------------------------------------------------------ 替身

    private final java.util.Map<String, Long> brokerIds = new java.util.LinkedHashMap<>();

    private void known(String... symbols) {
        for (String s : symbols) {
            brokerIds.put(s, 100L + brokerIds.size());
        }
        stubStatics();
    }

    private void unknown(String... symbols) {
        for (String s : symbols) {
            brokerIds.put(s, 0L);
        }
        stubStatics();
    }

    /** 照实测行为：富途对不认识的代码也回一条，brokerId=0。 */
    private void stubStatics() {
        when(gateway.staticInfo(anyList())).thenAnswer(inv -> {
            List<Instrument> asked = inv.getArgument(0);
            return CompletableFuture.completedFuture(asked.stream()
                    .map(i -> statics(i.symbol(), brokerIds.getOrDefault(i.symbol(), 0L)))
                    .toList());
        });
    }

    private void row(String symbol, long id, String status) {
        when(instruments.find(Instrument.us(symbol))).thenReturn(Optional.of(
                new InstrumentRow(id, Market.US, symbol, symbol, null, SecurityType.STOCK, 1, null, false, null,
                        brokerIds.getOrDefault(symbol, 0L), status)));
    }

    private static InstrumentStatic statics(String symbol, long brokerId) {
        return new InstrumentStatic(Instrument.us(symbol), brokerId == 0 ? "未知股票" : symbol,
                brokerId == 0 ? SecurityType.OTHER : SecurityType.STOCK, 1, null, brokerId == 0, null, brokerId);
    }

    private static List<Instrument> us(String... symbols) {
        return java.util.Arrays.stream(symbols).map(Instrument::us).toList();
    }

    /** 券商明确拒绝，带富途原文。 */
    private static Throwable rejected(String retMsg) {
        Set<String> named = retMsg.startsWith("未知股票 ") ? Set.of(retMsg.substring(5).trim()) : Set.of();
        return new ExecutionException(named.isEmpty()
                ? new RequestRejectedException(Broker.FUTU, -1, "getSecuritySnapshot 失败：" + retMsg + "（retType=-1）")
                : new UnknownSymbolException(Broker.FUTU, -1,
                        "getSecuritySnapshot 失败：" + retMsg + "（retType=-1）", named));
    }
}
