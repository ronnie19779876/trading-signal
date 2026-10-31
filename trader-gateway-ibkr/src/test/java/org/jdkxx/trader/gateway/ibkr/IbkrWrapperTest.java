package org.jdkxx.trader.gateway.ibkr;

import static org.assertj.core.api.Assertions.assertThat;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Decimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.jdkxx.trader.gateway.RequestRejectedException;
import org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IbkrWrapperTest {

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final List<String> events = new ArrayList<>();
  private final IbkrRequestRegistry registry =
      new IbkrRequestRegistry(scheduler, Runnable::run, Duration.ofSeconds(5));
  private final IbkrSubscriptions subscriptions = new IbkrSubscriptions(Runnable::run);
  private final IbkrWrapper wrapper =
      new IbkrWrapper(
          new IbkrWrapper.ConnectionEvents() {
            @Override
            public void onConnectAck() {
              events.add("ack");
            }

            @Override
            public void onNextValidId(int orderId) {
              events.add("nextValidId=" + orderId);
            }

            @Override
            public void onManagedAccounts(String accounts) {
              events.add("accounts");
            }

            @Override
            public void onCurrentTime(long epochSeconds) {
              events.add("time=" + epochSeconds);
            }

            @Override
            public void onConnectionClosed() {
              events.add("closed");
            }

            @Override
            public void onSystemMessage(int code, String message) {
              events.add("sys=" + code);
            }
          },
          registry,
          subscriptions);

  @AfterEach
  void tearDown() {
    scheduler.shutdownNow();
  }

  @Test
  void 连接级回调转给连接对象() {
    wrapper.connectAck();
    wrapper.nextValidId(7);
    wrapper.currentTime(100);
    wrapper.error(-1, 0, 2104, "Market data farm connection is OK:usfarm", null);
    wrapper.connectionClosed();

    assertThat(events).containsExactly("ack", "nextValidId=7", "time=100", "sys=2104", "closed");
  }

  @Test
  void 带reqId的回调与错误交给注册表() {
    int id = registry.nextId();
    CompletableFuture<List<ContractDetails>> f =
        registry.open(id, "t", new PendingRequest.Many<>(ContractDetails.class), null);
    wrapper.contractDetails(id, new ContractDetails());
    wrapper.contractDetailsEnd(id);
    assertThat(f).isCompleted();
    assertThat(f.join()).hasSize(1);

    int id2 = registry.nextId();
    CompletableFuture<List<ContractDetails>> g =
        registry.open(id2, "t", new PendingRequest.Many<>(ContractDetails.class), null);
    wrapper.error(id2, 0, 200, "No security definition has been found for the request", null);
    assertThat(g).isCompletedExceptionally();
    assertThat(
            g.handle(
                    (v, ex) -> {
                      Throwable c =
                          ex instanceof java.util.concurrent.CompletionException
                              ? ex.getCause()
                              : ex;
                      return c instanceof RequestRejectedException r && r.code() == 200
                          ? "200"
                          : "?";
                    })
                .join())
        .isEqualTo("200");
    assertThat(events).doesNotContain("sys=200");
  }

  @Test
  void 历史日线逐根交给注册表_End收尾() {
    int id = registry.nextId();
    CompletableFuture<List<com.ib.client.Bar>> f =
        registry.open(id, "t", new PendingRequest.Many<>(com.ib.client.Bar.class), null);
    wrapper.historicalData(
        id, new com.ib.client.Bar("20160104", 1, 2, 0.5, 1.5, Decimal.get(100), 1, Decimal.get(1)));
    wrapper.historicalData(
        id, new com.ib.client.Bar("20160105", 1, 2, 0.5, 1.6, Decimal.get(100), 1, Decimal.get(1)));
    wrapper.historicalDataEnd(id, "20160101", "20160131");

    assertThat(f.join())
        .extracting(com.ib.client.Bar::time)
        .containsExactly("20160104", "20160105");
  }

  /**
   * Earliest data time has no End callback: the single callback must finish the request, otherwise
   * it hangs until the timeout (the same symptom as a missing SPI registration on the Futu side).
   */
  @Test
  void 最早数据时间一次回调即完成() {
    int id = registry.nextId();
    CompletableFuture<String> f =
        registry.open(id, "t", new PendingRequest.Single<>(String.class), null);
    wrapper.headTimestamp(id, "20250807-13:30:00");

    assertThat(f).isCompletedWithValue("20250807-13:30:00");
    assertThat(registry.isPending(id)).isFalse();
  }

  @Test
  void 持仓与账户汇总回调交给注册表_结束后的迟到推送被忽略() {
    int id = registry.nextId();
    CompletableFuture<List<IbkrAccounts.PositionRow>> f =
        registry.open(id, "t", new PendingRequest.Many<>(IbkrAccounts.PositionRow.class), null);
    wrapper.positionMulti(id, "ACCT-A", "", new Contract(), Decimal.get(1), 10.0);
    wrapper.positionMultiEnd(id);
    assertThat(f.join()).hasSize(1);

    int id2 = registry.nextId();
    CompletableFuture<List<IbkrAccounts.SummaryRow>> g =
        registry.open(id2, "t", new PendingRequest.Many<>(IbkrAccounts.SummaryRow.class), null);
    wrapper.accountSummary(id2, "ACCT-A", "NetLiquidation", "1", "USD");
    wrapper.accountSummaryEnd(id2);
    assertThat(g.join()).extracting(IbkrAccounts.SummaryRow::tag).containsExactly("NetLiquidation");

    // measured: after a cancel the broker still pushes one or two rows; the request has ended, so
    // they must not throw on the pump thread and are not system messages
    wrapper.accountSummary(id2, "ACCT-A", "NetLiquidation", "1", "USD");
    wrapper.positionMulti(id, "ACCT-A", "", new Contract(), Decimal.get(1), 10.0);
    assertThat(events).isEmpty();
  }

  /**
   * Guard: a rejection of a standing subscription (for example the account summary exceeding the
   * 2-per-client limit, 322) goes to the subscriber instead of being swallowed as a system message.
   */
  @Test
  void 常驻订阅的错误与推送转给订阅处理器() {
    int id = registry.nextId();
    List<String> got = new ArrayList<>();
    subscriptions.open(
        id,
        new IbkrSubscriptions.Handler() {
          @Override
          public void item(Object item) {
            got.add(item.getClass().getSimpleName());
          }

          @Override
          public void error(RequestRejectedException e) {
            got.add("error=" + e.code());
          }
        });

    wrapper.pnl(id, 1.0, 2.0, 0.0);
    wrapper.pnlSingle(id, Decimal.get(10), 1.0, 2.0, Double.MAX_VALUE, 100.0);
    wrapper.marketDataType(id, 1);
    wrapper.tickPrice(id, 4, 346.08, new com.ib.client.TickAttrib());
    wrapper.error(id, 0, 322, "Maximum number of account summary requests exceeded", null);
    wrapper.pnl(id + 1, 1.0, 2.0, 0.0); // unknown ids are ignored

    assertThat(got)
        .containsExactly("PnlRow", "PnlSingleRow", "MarketDataTypeRow", "TickRow", "error=322");
    assertThat(events).doesNotContain("sys=322");
  }

  /**
   * The account-updates feed has no reqId: updatePortfolio / accountDownloadEnd use the fixed
   * channel; 2100 / 2101 are forwarded to it and also handled as system messages as usual.
   */
  @Test
  void 账户更新推送走固定通道_2100转给它() {
    List<Object> items = new ArrayList<>();
    List<String> ends = new ArrayList<>();
    List<Integer> errors = new ArrayList<>();
    subscriptions.open(
        IbkrSubscriptions.PORTFOLIO,
        new IbkrSubscriptions.Handler() {
          @Override
          public void item(Object item) {
            items.add(item);
          }

          @Override
          public void end() {
            ends.add("end");
          }

          @Override
          public void error(org.jdkxx.trader.gateway.RequestRejectedException e) {
            errors.add(e.code());
          }
        });
    Contract c = new Contract();
    c.conid(208813720);

    wrapper.updatePortfolio(
        c, Decimal.get(162), 329.6, 53395.2, 339.52469445, -1607.8, 0, "ACCT-A");
    wrapper.accountDownloadEnd("ACCT-A");
    wrapper.error(-1, 0L, 2100, "API client has been unsubscribed from account data.", null);

    assertThat(items).hasSize(1);
    assertThat(
            ((org.jdkxx.trader.gateway.ibkr.mapper.IbkrAccounts.PortfolioRow) items.get(0))
                .averageCost())
        .isEqualTo(339.52469445);
    assertThat(ends).containsExactly("end");
    assertThat(errors).containsExactly(2100);
    assertThat(events).contains("sys=2100");
  }
}
