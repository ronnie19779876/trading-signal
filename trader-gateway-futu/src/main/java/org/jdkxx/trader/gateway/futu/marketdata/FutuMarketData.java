package org.jdkxx.trader.gateway.futu.marketdata;

import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetCompanyProfile;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.futu.openapi.pb.QotGetKL;
import com.futu.openapi.pb.QotGetSecuritySnapshot;
import com.futu.openapi.pb.QotGetSubInfo;
import com.futu.openapi.pb.QotGetStaticInfo;
import com.futu.openapi.pb.QotRequestHistoryKL;
import com.futu.openapi.pb.QotRequestHistoryKLQuota;
import com.futu.openapi.pb.QotRequestRehab;
import com.futu.openapi.pb.QotRequestTradeDate;
import com.futu.openapi.pb.QotSub;
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
import org.jdkxx.trader.gateway.futu.mapper.FutuBars;
import org.jdkxx.trader.gateway.futu.mapper.FutuFundamentals;
import org.jdkxx.trader.gateway.futu.mapper.FutuRehabs;
import org.jdkxx.trader.gateway.futu.mapper.FutuSecurities;
import org.jdkxx.trader.gateway.futu.mapper.FutuStatics;
import org.jdkxx.trader.gateway.futu.QotCalls;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * 行情通道上的行情数据请求。所有 K 线一律不复权（RehabType_None），全字段。
 */
public final class FutuMarketData {

    /** KLFields 全部位：High|Open|Low|Close|LastClose|Volume|Turnover|TurnoverRate|PE|ChangeRate。 */
    static final long ALL_KL_FIELDS = 1023;
    static final int KL_DAY = QotCommon.KLType.KLType_Day_VALUE;
    static final int SUB_KL_DAY = QotCommon.SubType.SubType_KL_Day_VALUE;
    static final int HISTORY_PAGE = 1000;
    /** 券商对财报期数的单次上限。 */
    static final int MAX_FINANCIAL_PERIODS = 50;
    private static final ZoneId HK = ZoneId.of("Asia/Hong_Kong");

    private final QotCalls qot;

    public FutuMarketData(QotCalls qot) {
        this.qot = qot;
    }

    public CompletableFuture<List<InstrumentStatic>> staticInfo(List<Instrument> instruments) {
        QotGetStaticInfo.C2S.Builder c2s = QotGetStaticInfo.C2S.newBuilder();
        instruments.forEach(i -> c2s.addSecurityList(FutuSecurities.of(i)));
        QotGetStaticInfo.Request req = QotGetStaticInfo.Request.newBuilder().setC2S(c2s).build();
        return qot.call("get-static-info", "getStaticInfo", QotGetStaticInfo.Response.class, c -> c.getStaticInfo(req))
                .thenApply(rsp -> FutuStatics.toStatics(rsp.getS2C().getStaticInfoListList()));
    }

    public CompletableFuture<HistoryQuota> historyQuota() {
        QotRequestHistoryKLQuota.Request req = QotRequestHistoryKLQuota.Request.newBuilder()
                .setC2S(QotRequestHistoryKLQuota.C2S.newBuilder().setBGetDetail(true)).build();
        return qot.call("request-history-kl-quota", "requestHistoryKLQuota", QotRequestHistoryKLQuota.Response.class,
                        c -> c.requestHistoryKLQuota(req))
                .thenApply(rsp -> {
                    List<HistoryQuota.Item> items = new ArrayList<>();
                    for (QotRequestHistoryKLQuota.DetailItem d : rsp.getS2C().getDetailListList()) {
                        items.add(new HistoryQuota.Item(
                                new Instrument(FutuSecurities.market(d.getSecurity().getMarket()), d.getSecurity().getCode()),
                                d.getName(), Instant.ofEpochSecond(d.getRequestTimeStamp())));
                    }
                    return new HistoryQuota(rsp.getS2C().getUsedQuota(), rsp.getS2C().getRemainQuota(), items);
                });
    }

    /** 分页拉完 [from, to]；首页受限频，续页按文档不受限（这里仍走同一限流器，保守）。 */
    public CompletableFuture<List<DailyBar>> historyDailyBars(Instrument instrument, LocalDate from, LocalDate to) {
        return historyDailyBars(instrument, from, to, QotCommon.RehabType.RehabType_None_VALUE);
    }

    /** 指定复权类型的历史 K 线，只用于核对读取层复权（业务存储一律不复权）。 */
    public CompletableFuture<List<DailyBar>> historyDailyBars(Instrument instrument, LocalDate from, LocalDate to, int rehabType) {
        List<DailyBar> acc = new ArrayList<>();
        return historyPage(instrument, from, to, rehabType, null, acc);
    }

    private CompletableFuture<List<DailyBar>> historyPage(Instrument instrument, LocalDate from, LocalDate to, int rehabType,
                                                          org.jdkxx.trader.shaded.futu.protobuf.ByteString nextKey,
                                                          List<DailyBar> acc) {
        QotRequestHistoryKL.C2S.Builder c2s = QotRequestHistoryKL.C2S.newBuilder()
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
        return qot.call("request-history-kl", "requestHistoryKL " + instrument.symbol(), QotRequestHistoryKL.Response.class,
                        c -> c.requestHistoryKL(req))
                .thenCompose(rsp -> {
                    acc.addAll(FutuBars.toDailyBars(instrument, rsp.getS2C().getKlListList()));
                    if (rsp.getS2C().hasNextReqKey() && !rsp.getS2C().getNextReqKey().isEmpty()
                            && rsp.getS2C().getKlListCount() > 0) {
                        return historyPage(instrument, from, to, rehabType, rsp.getS2C().getNextReqKey(), acc);
                    }
                    return CompletableFuture.completedFuture(List.copyOf(acc));
                });
    }

    static final int SUB_BASIC = QotCommon.SubType.SubType_Basic_VALUE;

    public CompletableFuture<Void> subscribeDailyBars(List<Instrument> instruments) {
        return sub(instruments, SUB_KL_DAY, true, false);
    }

    public CompletableFuture<Void> unsubscribeDailyBars(List<Instrument> instruments) {
        return sub(instruments, SUB_KL_DAY, false, false);
    }

    /** 基础报价：注册推送，订阅成功后立即首推一条。 */
    public CompletableFuture<Void> subscribeQuotes(List<Instrument> instruments) {
        return sub(instruments, SUB_BASIC, true, true);
    }

    public CompletableFuture<Void> unsubscribeQuotes(List<Instrument> instruments) {
        return sub(instruments, SUB_BASIC, false, true);
    }

    private CompletableFuture<Void> sub(List<Instrument> instruments, int subType, boolean subscribe, boolean push) {
        if (instruments.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        QotSub.C2S.Builder c2s = QotSub.C2S.newBuilder()
                .addSubTypeList(subType)
                .setIsSubOrUnSub(subscribe)
                .setIsRegOrUnRegPush(push)
                .setIsFirstPush(push);
        instruments.forEach(i -> c2s.addSecurityList(FutuSecurities.of(i)));
        QotSub.Request req = QotSub.Request.newBuilder().setC2S(c2s).build();
        String type = subType == SUB_BASIC ? "Basic" : "KL_Day";
        return qot.call("sub", (subscribe ? "sub " : "unsub ") + type + " ×" + instruments.size(), QotSub.Response.class,
                c -> c.sub(req)).thenApply(r -> null);
    }

    public CompletableFuture<SubscriptionInfo> subscriptionInfo() {
        QotGetSubInfo.Request req = QotGetSubInfo.Request.newBuilder()
                .setC2S(QotGetSubInfo.C2S.newBuilder().setIsReqAllConn(true)).build();
        return qot.call("get-sub-info", "getSubInfo", QotGetSubInfo.Response.class, c -> c.getSubInfo(req))
                .thenApply(rsp -> {
                    Map<String, Integer> byType = new TreeMap<>();
                    for (QotCommon.ConnSubInfo conn : rsp.getS2C().getConnSubInfoListList()) {
                        if (!conn.getIsOwnConnData()) {
                            continue;
                        }
                        for (QotCommon.SubInfo si : conn.getSubInfoListList()) {
                            QotCommon.SubType t = QotCommon.SubType.forNumber(si.getSubType());
                            String name = t == null ? "T" + si.getSubType() : t.name().replace("SubType_", "");
                            byType.merge(name, si.getSecurityListCount(), Integer::sum);
                        }
                    }
                    return new SubscriptionInfo(rsp.getS2C().getTotalUsedQuota(), rsp.getS2C().getRemainQuota(), byType, Instant.now());
                });
    }

    public CompletableFuture<List<DailyBar>> recentDailyBars(Instrument instrument, int count) {
        QotGetKL.Request req = QotGetKL.Request.newBuilder().setC2S(QotGetKL.C2S.newBuilder()
                .setRehabType(QotCommon.RehabType.RehabType_None_VALUE)
                .setKlType(KL_DAY)
                .setSecurity(FutuSecurities.of(instrument))
                .setReqNum(Math.max(1, Math.min(count, 1000)))).build();
        return qot.call("get-kl", "getKL " + instrument.symbol(), QotGetKL.Response.class, c -> c.getKL(req))
                .thenApply(rsp -> FutuBars.toDailyBars(instrument, rsp.getS2C().getKlListList()));
    }

    public CompletableFuture<List<RehabFactor>> rehab(Instrument instrument) {
        QotRequestRehab.Request req = QotRequestRehab.Request.newBuilder()
                .setC2S(QotRequestRehab.C2S.newBuilder().setSecurity(FutuSecurities.of(instrument))).build();
        return qot.call("request-rehab", "requestRehab " + instrument.symbol(), QotRequestRehab.Response.class,
                        c -> c.requestRehab(req))
                .thenApply(rsp -> FutuRehabs.toFactors(instrument, rsp.getS2C().getRehabListList()));
    }

    public CompletableFuture<List<TradingDay>> tradingDays(Market market, LocalDate from, LocalDate to) {
        int m = market == Market.HK ? QotCommon.TradeDateMarket.TradeDateMarket_HK_VALUE
                : QotCommon.TradeDateMarket.TradeDateMarket_US_VALUE;
        QotRequestTradeDate.Request req = QotRequestTradeDate.Request.newBuilder().setC2S(QotRequestTradeDate.C2S.newBuilder()
                .setMarket(m).setBeginTime(from.toString()).setEndTime(to.toString())).build();
        return qot.call("request-trade-date", "requestTradeDate " + market, QotRequestTradeDate.Response.class,
                        c -> c.requestTradeDate(req))
                .thenApply(rsp -> {
                    List<TradingDay> out = new ArrayList<>();
                    for (QotRequestTradeDate.TradeDate d : rsp.getS2C().getTradeDateListList()) {
                        out.add(new TradingDay(market, LocalDate.parse(d.getTime().substring(0, 10)),
                                d.hasTradeDateType() ? d.getTradeDateType() : 0));
                    }
                    return out;
                });
    }

    static LocalDateTime nowHk() {
        return LocalDateTime.now(HK);
    }

    // ------------------------------------------------------------------ 基本面（步骤 3）

    /** 估值快照：不需要订阅，也不消耗历史 K 线额度。一次最多 400 只，分批由调用方决定。 */
    public CompletableFuture<List<ValuationSnapshot>> snapshots(List<Instrument> instruments) {
        QotGetSecuritySnapshot.C2S.Builder c2s = QotGetSecuritySnapshot.C2S.newBuilder();
        instruments.forEach(i -> c2s.addSecurityList(FutuSecurities.of(i)));
        QotGetSecuritySnapshot.Request req = QotGetSecuritySnapshot.Request.newBuilder().setC2S(c2s).build();
        return qot.call("get-security-snapshot", "getSecuritySnapshot", QotGetSecuritySnapshot.Response.class,
                        c -> c.getSecuritySnapshot(req))
                .thenApply(rsp -> FutuFundamentals.toSnapshots(rsp.getS2C().getSnapshotListList()));
    }

    /**
     * 财务报表：一次一只，num 是期数（券商上限 50）。默认 QuarterlyAnnual，季报年报混排，
     * 这样一次就能拿到季度序列而不用分两种周期各拉一遍。
     */
    public CompletableFuture<List<FinancialReport>> financials(Instrument instrument, FinancialStatement statement, int periods) {
        QotGetFinancialsStatements.C2S c2s = QotGetFinancialsStatements.C2S.newBuilder()
                .setSecurity(FutuSecurities.of(instrument))
                .setStatementType(QotCommon.FinancialStatementsType.forNumber(FutuFundamentals.statementType(statement)))
                .setFinancialType(QotCommon.F10Type.F10Type_QuarterlyAnnual)
                .setNum(Math.clamp(periods, 1, MAX_FINANCIAL_PERIODS))
                .build();
        QotGetFinancialsStatements.Request req = QotGetFinancialsStatements.Request.newBuilder().setC2S(c2s).build();
        return qot.call("get-financials", "getFinancialsStatements", QotGetFinancialsStatements.Response.class,
                        c -> c.getFinancialsStatements(req))
                .thenApply(rsp -> FutuFundamentals.toReports(instrument, statement, rsp.getS2C()));
    }

    public CompletableFuture<CompanyProfile> companyProfile(Instrument instrument) {
        QotGetCompanyProfile.Request req = QotGetCompanyProfile.Request.newBuilder()
                .setC2S(QotGetCompanyProfile.C2S.newBuilder().setSecurity(FutuSecurities.of(instrument)))
                .build();
        return qot.call("get-company-profile", "getCompanyProfile", QotGetCompanyProfile.Response.class,
                        c -> c.getCompanyProfile(req))
                .thenApply(rsp -> FutuFundamentals.toProfile(instrument, rsp.getS2C()));
    }

}
