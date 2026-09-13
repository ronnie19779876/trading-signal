package org.jdkxx.trader.app.integration;

import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentInfo;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.gateway.GatewayState;
import org.jdkxx.trader.gateway.ibkr.IbkrGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对真实 IB Gateway 的只读集成测试：连接 → 服务器时间 → 受管账户 → 合约查询 → 断开。
 */
@EnabledIfSystemProperty(named = "trader.integration", matches = "true")
class IbkrGatewayIT {

    @Test
    void 连接查询断开() throws Exception {
        String host = IntegrationEnv.env("TRADER_IBKR_HOST");
        int port = Integer.parseInt(IntegrationEnv.env("TRADER_IBKR_PORT"));
        int clientId = IntegrationEnv.envInt("TRADER_IBKR_TEST_CLIENT_ID", 91);

        try (IbkrGateway gateway = new IbkrGateway(IntegrationEnv.ibkr(host, port, clientId, Duration.ofSeconds(2)))) {
            gateway.connect().get(20, TimeUnit.SECONDS);
            assertThat(gateway.status().state()).isEqualTo(GatewayState.CONNECTED);
            assertThat(gateway.status().facts()).containsKey("serverVersion");
            System.out.println("[IT] ibkr facts=" + gateway.status().facts());

            Instant serverTime = gateway.serverTime().get(10, TimeUnit.SECONDS);
            assertThat(Duration.between(serverTime, Instant.now()).abs()).isLessThan(Duration.ofSeconds(60));

            List<AccountRef> accounts = gateway.accounts().get(10, TimeUnit.SECONDS);
            assertThat(accounts).isNotEmpty();
            System.out.println("[IT] ibkr accounts=" + accounts);

            List<InstrumentInfo> aapl = gateway.lookup(Instrument.us("AAPL")).get(15, TimeUnit.SECONDS);
            assertThat(aapl).isNotEmpty();
            InstrumentInfo info = aapl.get(0);
            assertThat(info.brokerRef()).isNotBlank();
            assertThat(info.minTick()).isNotNull();
            System.out.println("[IT] ibkr AAPL conId=" + info.brokerRef() + " name=" + info.name() + " minTick=" + info.minTick()
                    + " tz=" + info.timeZone() + " primary=" + info.primaryExchange() + " liquidHours=" + info.liquidHours());

            List<InstrumentInfo> none = gateway.lookup(Instrument.us("ZZZZNOSUCH")).get(15, TimeUnit.SECONDS);
            assertThat(none).isEmpty();

            gateway.disconnect();
            assertThat(gateway.status().state()).isEqualTo(GatewayState.DISCONNECTED);
        }
    }

    /**
     * 第 3 期账户接口：持仓与资金汇总（只读）。仓库公开，输出只打条数、比例与脱敏信息，不打金额与账户号。
     */
    @Test
    void 持仓与账户汇总() throws Exception {
        String host = IntegrationEnv.env("TRADER_IBKR_HOST");
        int port = Integer.parseInt(IntegrationEnv.env("TRADER_IBKR_PORT"));
        int clientId = IntegrationEnv.envInt("TRADER_IBKR_TEST_CLIENT_ID", 91);

        try (IbkrGateway gateway = new IbkrGateway(IntegrationEnv.ibkr(host, port, clientId, Duration.ofSeconds(2)))) {
            gateway.connect().get(20, TimeUnit.SECONDS);
            String accountId = gateway.accounts().get(10, TimeUnit.SECONDS).get(0).accountId();

            List<Position> positions = gateway.positions(accountId).get(20, TimeUnit.SECONDS);
            assertThat(positions).allSatisfy(p -> {
                assertThat(p.brokerRef()).isNotBlank();
                assertThat(p.quantity()).isNotNull();
                assertThat(p.securityType()).isNotBlank();
                assertThat(p.accountId()).isEqualTo(accountId);
                assertThat(p.toString()).doesNotContain(accountId);
            });
            System.out.println("[IT] ibkr positions=" + positions.size()
                    + " secTypes=" + positions.stream().map(Position::securityType).distinct().toList()
                    + " 代码带空格=" + positions.stream().filter(p -> p.symbol() != null && p.symbol().contains(" ")).count()
                    + " primaryExch为空=" + positions.stream().filter(p -> p.primaryExchange() == null).count());

            // 订阅式请求取消得干净的话，紧接着再查一次照常返回、条数一致
            assertThat(gateway.positions(accountId).get(20, TimeUnit.SECONDS)).hasSameSizeAs(positions);

            // 并发两次汇总合并成一次券商请求：拿到同一个结果对象
            CompletableFuture<AccountSummary> first = gateway.accountSummary(accountId);
            CompletableFuture<AccountSummary> second = gateway.accountSummary(accountId);
            AccountSummary s = first.get(20, TimeUnit.SECONDS);
            assertThat(second.get(20, TimeUnit.SECONDS)).isSameAs(s);

            assertThat(s.currency()).isNotBlank();
            assertThat(s.netLiquidation()).isNotNull();
            assertThat(s.totalCash()).isNotNull();
            assertThat(s.stockMarketValue()).isNotNull();
            assertThat(s.raw()).doesNotContainKey("$LEDGER-AccountOrGroup");
            assertThat(s.raw().values()).noneMatch(v -> v.contains(accountId));
            assertThat(s.toString()).doesNotContain(accountId);

            String identity = "应计股息缺失，未核";
            if (s.accruedDividend() != null && s.netLiquidation().signum() != 0) {
                BigDecimal gap = s.totalCash().add(s.stockMarketValue()).add(s.accruedDividend()).subtract(s.netLiquidation()).abs();
                BigDecimal ratio = gap.divide(s.netLiquidation().abs(), MathContext.DECIMAL64);
                // 盘中各标签约 3 分钟更新一次，可能短暂错位；休市时实测精确相等
                assertThat(ratio).isLessThan(new BigDecimal("0.01"));
                identity = "偏差占净值 " + ratio.movePointRight(2).setScale(4, java.math.RoundingMode.HALF_UP) + "%";
            }
            System.out.println("[IT] ibkr summary currency=" + s.currency() + " tags=" + s.raw().size()
                    + " 缺失字段=" + missing(s) + " 恒等式(现金+股票+应计股息=净值) " + identity);

            // 取消干净的话，顺序再请求一次不会被网关拒绝，且是一次新的请求
            AccountSummary again = gateway.accountSummary(accountId).get(20, TimeUnit.SECONDS);
            assertThat(again).isNotSameAs(s);
            assertThat(again.netLiquidation()).isNotNull();

            gateway.disconnect();
        }
    }

    private static List<String> missing(AccountSummary s) {
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("grossPositionValue", s.grossPositionValue());
        fields.put("availableFunds", s.availableFunds());
        fields.put("buyingPower", s.buyingPower());
        fields.put("excessLiquidity", s.excessLiquidity());
        fields.put("unrealizedPnl", s.unrealizedPnl());
        fields.put("realizedPnl", s.realizedPnl());
        fields.put("accruedDividend", s.accruedDividend());
        return fields.entrySet().stream().filter(e -> e.getValue() == null).map(java.util.Map.Entry::getKey).toList();
    }
}
