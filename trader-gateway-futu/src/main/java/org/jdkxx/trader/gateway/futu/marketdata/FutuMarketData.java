package org.jdkxx.trader.gateway.futu.marketdata;

import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetCompanyProfile;
import com.futu.openapi.pb.QotGetFinancialsEarningsPriceHistory;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.futu.openapi.pb.QotGetKL;
import com.futu.openapi.pb.QotGetSecuritySnapshot;
import com.futu.openapi.pb.QotGetStaticInfo;
import com.futu.openapi.pb.QotGetSubInfo;
import com.futu.openapi.pb.QotGetValuationDetail;
import com.futu.openapi.pb.QotRequestHistoryKL;
import com.futu.openapi.pb.QotRequestHistoryKLQuota;
import com.futu.openapi.pb.QotRequestRehab;
import com.futu.openapi.pb.QotRequestTradeDate;
import com.futu.openapi.pb.QotSub;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.CompanyProfile;
import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.HistoryQuota;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentStatic;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;
import org.jdkxx.trader.domain.SubscriptionInfo;
import org.jdkxx.trader.domain.TradingDay;
import org.jdkxx.trader.domain.ValuationSnapshot;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.ValuationMetric;
import org.jdkxx.trader.domain.selection.ValuationPoint;
import org.jdkxx.trader.domain.selection.ValuationRange;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.futu.QotCalls;
import org.jdkxx.trader.gateway.futu.mapper.FutuBars;
import org.jdkxx.trader.gateway.futu.mapper.FutuFundamentals;
import org.jdkxx.trader.gateway.futu.mapper.FutuRehabs;
import org.jdkxx.trader.gateway.futu.mapper.FutuSecurities;
import org.jdkxx.trader.gateway.futu.mapper.FutuSelection;
import org.jdkxx.trader.gateway.futu.mapper.FutuStatics;

/**
 * Market-data requests on the market-data channel. All bars are unadjusted (RehabType_None) and
 * request every field.
 *
 * <p>Each method builds the request and maps the reply; rate limiting, sending and reply matching
 * are done by {@link QotCalls}. Mapping callbacks ({@code thenApply}) run on futu-dispatch, so they
 * must stay cheap and must never issue another request; a follow-up request switches threads with
 * {@code thenComposeAsync}.
 */
public final class FutuMarketData {

  /**
   * All KLFields bits: High|Open|Low|Close|LastClose|Volume|Turnover|TurnoverRate|PE|ChangeRate.
   */
  static final long ALL_KL_FIELDS = 1023;

  static final int KL_DAY = QotCommon.KLType.KLType_Day_VALUE;
  static final int SUB_KL_DAY = QotCommon.SubType.SubType_KL_Day_VALUE;
  static final int HISTORY_PAGE = 1000;

  /** The broker's per-request maximum number of financial-report periods. */
  static final int MAX_FINANCIAL_PERIODS = 50;

  private static final ZoneId HK = ZoneId.of("Asia/Hong_Kong");

  private final QotCalls qot;

  /** Thread used to send follow-up pages (see historyPage). */
  private final Executor pager;

  public FutuMarketData(QotCalls qot, Executor pager) {
    this.qot = qot;
    this.pager = pager;
  }

  public CompletableFuture<List<InstrumentStatic>> staticInfo(List<Instrument> instruments) {
    QotGetStaticInfo.C2S.Builder c2s = QotGetStaticInfo.C2S.newBuilder();
    instruments.forEach(i -> c2s.addSecurityList(FutuSecurities.of(i)));
    QotGetStaticInfo.Request req = QotGetStaticInfo.Request.newBuilder().setC2S(c2s).build();
    return qot.call(
            "get-static-info",
            "getStaticInfo",
            QotGetStaticInfo.Response.class,
            c -> c.getStaticInfo(req))
        .thenApply(rsp -> FutuStatics.toStatics(rsp.getS2C().getStaticInfoListList()));
  }

  public CompletableFuture<HistoryQuota> historyQuota() {
    QotRequestHistoryKLQuota.Request req =
        QotRequestHistoryKLQuota.Request.newBuilder()
            .setC2S(QotRequestHistoryKLQuota.C2S.newBuilder().setBGetDetail(true))
            .build();
    return qot.call(
            "request-history-kl-quota",
            "requestHistoryKLQuota",
            QotRequestHistoryKLQuota.Response.class,
            c -> c.requestHistoryKLQuota(req))
        .thenApply(
            rsp -> {
              List<HistoryQuota.Item> items = new ArrayList<>();
              for (QotRequestHistoryKLQuota.DetailItem d : rsp.getS2C().getDetailListList()) {
                items.add(
                    new HistoryQuota.Item(
                        new Instrument(
                            FutuSecurities.market(d.getSecurity().getMarket()),
                            d.getSecurity().getCode()),
                        d.getName(),
                        Instant.ofEpochSecond(d.getRequestTimeStamp())));
              }
              return new HistoryQuota(
                  rsp.getS2C().getUsedQuota(), rsp.getS2C().getRemainQuota(), items);
            });
  }

  /**
   * Fetches [from, to] page by page. Per the docs only the first page is rate limited and follow-up
   * pages are not (here they still go through the same limiter, to be conservative).
   */
  public CompletableFuture<List<DailyBar>> historyDailyBars(
      Instrument instrument, LocalDate from, LocalDate to) {
    return historyDailyBars(instrument, from, to, QotCommon.RehabType.RehabType_None_VALUE);
  }

  /**
   * Historical bars with an explicit adjustment type, used only to cross-check the read-layer
   * adjustment (business storage is always unadjusted).
   */
  public CompletableFuture<List<DailyBar>> historyDailyBars(
      Instrument instrument, LocalDate from, LocalDate to, int rehabType) {
    List<DailyBar> acc = new ArrayList<>();
    return historyPage(instrument, from, to, rehabType, null, 1, maxHistoryPages(from, to), acc);
  }

  /**
   * Upper bound on pages for [from, to]: one page per HISTORY_PAGE calendar days (trading days are
   * fewer, so this already over-counts) plus slack. Found in the 2026-10-31 review: nothing bounded
   * the recursion, so a broker that kept answering "next" would page forever on the pager thread.
   */
  static int maxHistoryPages(LocalDate from, LocalDate to) {
    long days = Math.max(0, ChronoUnit.DAYS.between(from, to) + 1);
    return (int) Math.min(Integer.MAX_VALUE, (days + HISTORY_PAGE - 1) / HISTORY_PAGE + 5);
  }

  /**
   * One page, then recursively the next. {@code acc} is only touched on the pager thread (one page
   * at a time), so it needs no synchronisation. Paging stops when the broker returns no next key or
   * an empty page; it fails when the next key repeats the one just sent (the broker would return
   * the same page again) or when more than {@code maxPages} pages arrive.
   */
  private CompletableFuture<List<DailyBar>> historyPage(
      Instrument instrument,
      LocalDate from,
      LocalDate to,
      int rehabType,
      org.jdkxx.trader.shaded.futu.protobuf.ByteString nextKey,
      int pageNo,
      int maxPages,
      List<DailyBar> acc) {
    QotRequestHistoryKL.C2S.Builder c2s =
        QotRequestHistoryKL.C2S
            .newBuilder()
            .setRehabType(rehabType)
            .setKlType(KL_DAY)
            .setSecurity(FutuSecurities.of(instrument))
            .setBeginTime(from.toString())
            .setEndTime(to.toString())
            .setMaxAckKLNum(HISTORY_PAGE)
            .setNeedKLFieldsFlag(ALL_KL_FIELDS);
    if (nextKey != null) {
      c2s.setNextReqKey(nextKey);
    }
    QotRequestHistoryKL.Request req = QotRequestHistoryKL.Request.newBuilder().setC2S(c2s).build();
    return qot.call(
            "request-history-kl",
            "requestHistoryKL " + instrument.symbol(),
            QotRequestHistoryKL.Response.class,
            c -> c.requestHistoryKL(req))
        // The reply completes on futu-dispatch, and the next page first has to pass the rate
        // limiter (it may sleep for tens of seconds), so it must be sent from another thread
        // (before 2.0.2 it slept on dispatch, the heartbeat timed out and the connection was
        // dropped)
        .thenComposeAsync(
            rsp -> {
              requireSameSecurity(
                  instrument,
                  rsp.getS2C().hasSecurity(),
                  rsp.getS2C().getSecurity(),
                  "requestHistoryKL");
              acc.addAll(FutuBars.toDailyBars(instrument, rsp.getS2C().getKlListList()));
              if (rsp.getS2C().hasNextReqKey()
                  && !rsp.getS2C().getNextReqKey().isEmpty()
                  && rsp.getS2C().getKlListCount() > 0) {
                var next = rsp.getS2C().getNextReqKey();
                if (next.equals(nextKey)) {
                  throw new GatewayException(
                      Broker.FUTU,
                      0,
                      "requestHistoryKL "
                          + instrument.symbol()
                          + " 第 "
                          + pageNo
                          + " 页返回的翻页键与上一页相同，停止翻页",
                      false);
                }
                if (pageNo >= maxPages) {
                  throw new GatewayException(
                      Broker.FUTU,
                      0,
                      "requestHistoryKL "
                          + instrument.symbol()
                          + " 翻页超过上限 "
                          + maxPages
                          + " 页（"
                          + from
                          + " ~ "
                          + to
                          + "），停止翻页",
                      false);
                }
                return historyPage(
                    instrument, from, to, rehabType, next, pageNo + 1, maxPages, acc);
              }
              return CompletableFuture.completedFuture(List.copyOf(acc));
            },
            pager);
  }

  /**
   * The security in the reply must be the one requested: should a serial number ever be mismatched,
   * failing is better than filing another stock's bars under this one.
   */
  static void requireSameSecurity(
      Instrument instrument, boolean present, QotCommon.Security got, String what) {
    if (!present) {
      return;
    }
    QotCommon.Security want = FutuSecurities.of(instrument);
    if (got.getMarket() != want.getMarket() || !got.getCode().equalsIgnoreCase(want.getCode())) {
      throw new IllegalStateException(
          what + " 回复的标的与请求不一致（请求 " + instrument.symbol() + "，回复 " + got.getCode() + "）");
    }
  }

  static final int SUB_BASIC = QotCommon.SubType.SubType_Basic_VALUE;

  public CompletableFuture<Void> subscribeDailyBars(List<Instrument> instruments) {
    return sub(instruments, SUB_KL_DAY, true, false);
  }

  public CompletableFuture<Void> unsubscribeDailyBars(List<Instrument> instruments) {
    return sub(instruments, SUB_KL_DAY, false, false);
  }

  /**
   * Basic quotes: registers for pushes, and the broker pushes one quote right after the
   * subscription succeeds.
   */
  public CompletableFuture<Void> subscribeQuotes(List<Instrument> instruments) {
    return sub(instruments, SUB_BASIC, true, true);
  }

  public CompletableFuture<Void> unsubscribeQuotes(List<Instrument> instruments) {
    return sub(instruments, SUB_BASIC, false, true);
  }

  /**
   * Subscribe or unsubscribe one type for a batch. Quota is counted per security × subscription
   * type (100 per account, shared with every other instance on the same account), and Futu refuses
   * to unsubscribe until a subscription is at least one minute old; both rules are handled by the
   * callers in core, not here. {@code push} registers (or unregisters) this connection for push
   * delivery; daily-bar subscriptions are only used for getKL and need no pushes.
   */
  private CompletableFuture<Void> sub(
      List<Instrument> instruments, int subType, boolean subscribe, boolean push) {
    if (instruments.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }
    QotSub.C2S.Builder c2s =
        QotSub.C2S
            .newBuilder()
            .addSubTypeList(subType)
            .setIsSubOrUnSub(subscribe)
            .setIsRegOrUnRegPush(push)
            .setIsFirstPush(push);
    instruments.forEach(i -> c2s.addSecurityList(FutuSecurities.of(i)));
    QotSub.Request req = QotSub.Request.newBuilder().setC2S(c2s).build();
    String type = subType == SUB_BASIC ? "Basic" : "KL_Day";
    return qot.call(
            "sub",
            (subscribe ? "sub " : "unsub ") + type + " ×" + instruments.size(),
            QotSub.Response.class,
            c -> c.sub(req))
        .thenApply(r -> null);
  }

  /**
   * Subscription usage. The quota totals cover every connection on the account (other instances
   * included), while the per-type breakdown counts only this connection's own subscriptions.
   */
  public CompletableFuture<SubscriptionInfo> subscriptionInfo() {
    QotGetSubInfo.Request req =
        QotGetSubInfo.Request.newBuilder()
            .setC2S(QotGetSubInfo.C2S.newBuilder().setIsReqAllConn(true))
            .build();
    return qot.call(
            "get-sub-info", "getSubInfo", QotGetSubInfo.Response.class, c -> c.getSubInfo(req))
        .thenApply(
            rsp -> {
              Map<String, Integer> byType = new TreeMap<>();
              for (QotCommon.ConnSubInfo conn : rsp.getS2C().getConnSubInfoListList()) {
                if (!conn.getIsOwnConnData()) {
                  continue;
                }
                for (QotCommon.SubInfo si : conn.getSubInfoListList()) {
                  QotCommon.SubType t = QotCommon.SubType.forNumber(si.getSubType());
                  String name =
                      t == null ? "T" + si.getSubType() : t.name().replace("SubType_", "");
                  byType.merge(name, si.getSecurityListCount(), Integer::sum);
                }
              }
              return new SubscriptionInfo(
                  rsp.getS2C().getTotalUsedQuota(),
                  rsp.getS2C().getRemainQuota(),
                  byType,
                  Instant.now());
            });
  }

  public CompletableFuture<List<DailyBar>> recentDailyBars(Instrument instrument, int count) {
    QotGetKL.Request req =
        QotGetKL.Request.newBuilder()
            .setC2S(
                QotGetKL.C2S
                    .newBuilder()
                    .setRehabType(QotCommon.RehabType.RehabType_None_VALUE)
                    .setKlType(KL_DAY)
                    .setSecurity(FutuSecurities.of(instrument))
                    .setReqNum(Math.max(1, Math.min(count, 1000))))
            .build();
    return qot.call(
            "get-kl", "getKL " + instrument.symbol(), QotGetKL.Response.class, c -> c.getKL(req))
        .thenApply(
            rsp -> {
              requireSameSecurity(
                  instrument, rsp.getS2C().hasSecurity(), rsp.getS2C().getSecurity(), "getKL");
              return FutuBars.toDailyBars(instrument, rsp.getS2C().getKlListList());
            });
  }

  public CompletableFuture<List<RehabFactor>> rehab(Instrument instrument) {
    QotRequestRehab.Request req =
        QotRequestRehab.Request.newBuilder()
            .setC2S(QotRequestRehab.C2S.newBuilder().setSecurity(FutuSecurities.of(instrument)))
            .build();
    return qot.call(
            "request-rehab",
            "requestRehab " + instrument.symbol(),
            QotRequestRehab.Response.class,
            c -> c.requestRehab(req))
        .thenApply(rsp -> FutuRehabs.toFactors(instrument, rsp.getS2C().getRehabListList()));
  }

  public CompletableFuture<List<TradingDay>> tradingDays(
      Market market, LocalDate from, LocalDate to) {
    int m =
        market == Market.HK
            ? QotCommon.TradeDateMarket.TradeDateMarket_HK_VALUE
            : QotCommon.TradeDateMarket.TradeDateMarket_US_VALUE;
    QotRequestTradeDate.Request req =
        QotRequestTradeDate.Request.newBuilder()
            .setC2S(
                QotRequestTradeDate.C2S
                    .newBuilder()
                    .setMarket(m)
                    .setBeginTime(from.toString())
                    .setEndTime(to.toString()))
            .build();
    return qot.call(
            "request-trade-date",
            "requestTradeDate " + market,
            QotRequestTradeDate.Response.class,
            c -> c.requestTradeDate(req))
        .thenApply(
            rsp -> {
              List<TradingDay> out = new ArrayList<>();
              for (QotRequestTradeDate.TradeDate d : rsp.getS2C().getTradeDateListList()) {
                out.add(
                    new TradingDay(
                        market,
                        LocalDate.parse(d.getTime().substring(0, 10)),
                        d.hasTradeDateType() ? d.getTradeDateType() : 0));
              }
              return out;
            });
  }

  static LocalDateTime nowHk() {
    return LocalDateTime.now(HK);
  }

  // ------------------------------------------------------------------ Fundamentals (step 3)

  /**
   * Valuation snapshots: need no subscription and consume no historical-bar quota. At most 400
   * securities per call; batching is up to the caller.
   */
  public CompletableFuture<List<ValuationSnapshot>> snapshots(List<Instrument> instruments) {
    QotGetSecuritySnapshot.C2S.Builder c2s = QotGetSecuritySnapshot.C2S.newBuilder();
    instruments.forEach(i -> c2s.addSecurityList(FutuSecurities.of(i)));
    QotGetSecuritySnapshot.Request req =
        QotGetSecuritySnapshot.Request.newBuilder().setC2S(c2s).build();
    return qot.call(
            "get-security-snapshot",
            "getSecuritySnapshot",
            QotGetSecuritySnapshot.Response.class,
            c -> c.getSecuritySnapshot(req))
        .thenApply(rsp -> FutuFundamentals.toSnapshots(rsp.getS2C().getSnapshotListList()));
  }

  /**
   * Financial statements: one security per call, num is the number of periods (broker maximum 50).
   * The default is QuarterlyAnnual, quarterly and annual reports interleaved, so a single call
   * yields the quarterly series instead of fetching each period type separately.
   */
  public CompletableFuture<List<FinancialReport>> financials(
      Instrument instrument, FinancialStatement statement, int periods) {
    return financials(instrument, statement, periods, QotCommon.F10Type.F10Type_QuarterlyAnnual);
  }

  /**
   * Quarterly reports only (quant stock selection, §25.6): at most 50 quarters per page, which in
   * testing reaches back to about 2014, enough for year-over-year figures from 2016.
   */
  public CompletableFuture<List<FinancialReport>> quarterlyFinancials(
      Instrument instrument, FinancialStatement statement, int periods) {
    return financials(instrument, statement, periods, QotCommon.F10Type.F10Type_Quarterly);
  }

  private CompletableFuture<List<FinancialReport>> financials(
      Instrument instrument, FinancialStatement statement, int periods, QotCommon.F10Type type) {
    QotGetFinancialsStatements.C2S c2s =
        QotGetFinancialsStatements.C2S
            .newBuilder()
            .setSecurity(FutuSecurities.of(instrument))
            .setStatementType(
                QotCommon.FinancialStatementsType.forNumber(
                    FutuFundamentals.statementType(statement)))
            .setFinancialType(type)
            .setNum(Math.clamp(periods, 1, MAX_FINANCIAL_PERIODS))
            .build();
    QotGetFinancialsStatements.Request req =
        QotGetFinancialsStatements.Request.newBuilder().setC2S(c2s).build();
    return qot.call(
            "get-financials",
            "getFinancialsStatements",
            QotGetFinancialsStatements.Response.class,
            c -> c.getFinancialsStatements(req))
        .thenApply(rsp -> FutuFundamentals.toReports(instrument, statement, rsp.getS2C()));
  }

  /**
   * Daily valuation history (quant stock selection, §25.6). YEAR10 measured about 2513 days
   * starting 2016-10.
   */
  public CompletableFuture<List<ValuationPoint>> valuationHistory(
      Instrument instrument, ValuationMetric metric, ValuationRange range) {
    QotGetValuationDetail.Request req =
        QotGetValuationDetail.Request.newBuilder()
            .setC2S(
                QotGetValuationDetail.C2S
                    .newBuilder()
                    .setSecurity(FutuSecurities.of(instrument))
                    .setValuationType(FutuSelection.valuationType(metric))
                    .setIntervalType(FutuSelection.interval(range)))
            .build();
    return qot.call(
            "get-valuation-detail",
            "getValuationDetail",
            QotGetValuationDetail.Response.class,
            c -> c.getValuationDetail(req))
        .thenApply(rsp -> FutuSelection.toValuationPoints(rsp.getS2C()));
  }

  /**
   * Earnings announcement dates (quant stock selection, §25.6): measured to cover the latest 20
   * quarters, including scheduled future dates.
   */
  public CompletableFuture<List<EarningsEvent>> earningsHistory(Instrument instrument) {
    QotGetFinancialsEarningsPriceHistory.Request req =
        QotGetFinancialsEarningsPriceHistory.Request.newBuilder()
            .setC2S(
                QotGetFinancialsEarningsPriceHistory.C2S
                    .newBuilder()
                    .setSecurity(FutuSecurities.of(instrument)))
            .build();
    return qot.call(
            "get-earnings-history",
            "getFinancialsEarningsPriceHistory",
            QotGetFinancialsEarningsPriceHistory.Response.class,
            c -> c.getFinancialsEarningsPriceHistory(req))
        .thenApply(rsp -> FutuSelection.toEarningsEvents(rsp.getS2C()));
  }

  public CompletableFuture<CompanyProfile> companyProfile(Instrument instrument) {
    QotGetCompanyProfile.Request req =
        QotGetCompanyProfile.Request.newBuilder()
            .setC2S(
                QotGetCompanyProfile.C2S.newBuilder().setSecurity(FutuSecurities.of(instrument)))
            .build();
    return qot.call(
            "get-company-profile",
            "getCompanyProfile",
            QotGetCompanyProfile.Response.class,
            c -> c.getCompanyProfile(req))
        .thenApply(rsp -> FutuFundamentals.toProfile(instrument, rsp.getS2C()));
  }
}
