package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetCompanyProfile;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.futu.openapi.pb.QotGetSecuritySnapshot;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.ValuationSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FutuFundamentalsTest {

    private static QotCommon.Security us(String code) {
        return QotCommon.Security.newBuilder()
                .setMarket(QotCommon.QotMarket.QotMarket_US_Security_VALUE).setCode(code).build();
    }

    private static QotGetSecuritySnapshot.SnapshotBasicData.Builder basic(String code) {
        return QotGetSecuritySnapshot.SnapshotBasicData.newBuilder()
                .setSecurity(us(code)).setName(code).setType(QotCommon.SecurityType.SecurityType_Eqty_VALUE)
                .setIsSuspend(false).setListTime("2010-01-01").setLotSize(1).setPriceSpread(0.01)
                .setUpdateTime("2026-09-08 16:00:00").setHighPrice(1).setOpenPrice(1).setLowPrice(1)
                .setLastClosePrice(1).setCurPrice(1).setVolume(1).setTurnover(1).setTurnoverRate(0.83)
                .setUpdateTimestamp(1_757_361_600d);
    }

    /** 估值字段在 proto 里全是 required，构造时必须给全。 */
    private static QotGetSecuritySnapshot.EquitySnapshotExData.Builder equity() {
        return QotGetSecuritySnapshot.EquitySnapshotExData.newBuilder()
                .setIssuedShares(24_000_000_000L).setIssuedMarketVal(5.4e12)
                .setOutstandingShares(23_000_000_000L).setOutstandingMarketVal(5.2e12)
                .setPeRate(46.07).setPeTTMRate(28.54).setPbRate(23.76).setEyRate(0.83)
                .setEarningsPershare(4.9).setNetAssetPershare(5.0)
                .setNetAsset(2.29e11).setNetProfit(1.181e11);
    }

    private static QotGetSecuritySnapshot.TrustSnapshotExData.Builder trust() {
        return QotGetSecuritySnapshot.TrustSnapshotExData.newBuilder()
                .setDividendYield(0.98).setAum(8.12e11).setOutstandingUnits(1_054_132_116L)
                .setNetAssetValue(769.35).setPremium(-0.44).setAssetClass(0);
    }

    @Test
    void 个股取估值口径() {
        QotGetSecuritySnapshot.Snapshot s = QotGetSecuritySnapshot.Snapshot.newBuilder()
                .setBasic(basic("NVDA")).setEquityExData(equity().setDividendTTM(0.28).setDividendRatioTTM(0.12))
                .build();

        ValuationSnapshot v = FutuFundamentals.toSnapshots(List.of(s)).getFirst();

        assertThat(v.instrument()).isEqualTo(new Instrument(Market.US, "NVDA"));
        assertThat(v.marketCap()).isEqualByComparingTo(BigDecimal.valueOf(5.4e12));
        assertThat(v.pe()).isEqualByComparingTo("46.07");
        assertThat(v.peTtm()).isEqualByComparingTo("28.54");
        assertThat(v.turnoverRate()).isEqualByComparingTo("0.83");
        assertThat(v.asOf().getEpochSecond()).isEqualTo(1_757_361_600L);
        assertThat(v.dividendTtm()).isEqualByComparingTo("0.28");
        assertThat(v.navPerShare()).as("个股没有净值").isNull();
    }

    @Test
    void 亏损股的负市盈率是真实数据_原样保留() {
        // 实测 INTC peTTM=-49.99、LCID pb=-1.72：亏损与资不抵债都会给负数，不是缺失
        QotGetSecuritySnapshot.Snapshot s = QotGetSecuritySnapshot.Snapshot.newBuilder()
                .setBasic(basic("LCID"))
                .setEquityExData(equity().setPeRate(-0.382).setPeTTMRate(-0.339).setPbRate(-1.724)
                        .setEarningsPershare(-12.09).setNetProfit(-4.764e9).setNetAsset(-1.058e9)
                        .setDividendTTM(0).setDividendRatioTTM(0))
                .build();

        ValuationSnapshot v = FutuFundamentals.toSnapshots(List.of(s)).getFirst();

        assertThat(v.pe()).isEqualByComparingTo("-0.382");
        assertThat(v.pb()).isEqualByComparingTo("-1.724");
        assertThat(v.netProfit()).isEqualByComparingTo(BigDecimal.valueOf(-4.764e9));
        assertThat(v.dividendTtm()).as("不分红是真实的 0，不能转成空").isEqualByComparingTo("0");
    }

    @Test
    void ETF走另一套口径_资产规模与净值溢价() {
        QotGetSecuritySnapshot.Snapshot s = QotGetSecuritySnapshot.Snapshot.newBuilder()
                .setBasic(basic("SPY").setType(QotCommon.SecurityType.SecurityType_Trust_VALUE))
                .setTrustExData(trust())
                .build();

        ValuationSnapshot v = FutuFundamentals.toSnapshots(List.of(s)).getFirst();

        assertThat(v.netAsset()).as("资产规模记进 netAsset").isEqualByComparingTo(BigDecimal.valueOf(8.12e11));
        assertThat(v.outstandingShares()).isEqualTo(1_054_132_116L);
        assertThat(v.dividendYieldTtm()).isEqualByComparingTo("0.98");
        assertThat(v.navPerShare()).isEqualByComparingTo("769.35");
        assertThat(v.premium()).as("折价是负溢价").isEqualByComparingTo("-0.44");
        assertThat(v.pe()).as("ETF 没有市盈率").isNull();
        assertThat(v.marketCap()).as("ETF 没有市值口径").isNull();
    }

    @Test
    void 净值为0表示富途没有该数据_连同溢价一起作废() {
        // 实测标普 500 里的 25 只 REITs 也被富途归为 Trust，净值全为 0；只有真 ETF（SPY）有净值
        QotGetSecuritySnapshot.Snapshot s = QotGetSecuritySnapshot.Snapshot.newBuilder()
                .setBasic(basic("XYZ").setType(QotCommon.SecurityType.SecurityType_Trust_VALUE))
                .setTrustExData(trust().setNetAssetValue(0).setPremium(0))
                .build();

        ValuationSnapshot v = FutuFundamentals.toSnapshots(List.of(s)).getFirst();

        assertThat(v.navPerShare()).isNull();
        assertThat(v.premium()).isNull();
        assertThat(v.netAsset()).as("资产规模还是有的").isNotNull();
    }

    @Test
    void 既非个股也非ETF的只留标识与停牌() {
        QotGetSecuritySnapshot.Snapshot s = QotGetSecuritySnapshot.Snapshot.newBuilder()
                .setBasic(basic(".IXIC").setIsSuspend(true))
                .build();

        ValuationSnapshot v = FutuFundamentals.toSnapshots(List.of(s)).getFirst();

        assertThat(v.suspended()).isTrue();
        assertThat(v.marketCap()).isNull();
        assertThat(v.netAsset()).isNull();
    }

    @Test
    void 财报按字段字典还原名称_并保留同比环比() {
        QotGetFinancialsStatements.S2C s2c = QotGetFinancialsStatements.S2C.newBuilder()
                .addStructureList(QotGetFinancialsStatements.FinancialFieldInfo.newBuilder()
                        .setFieldId(1).setDisplayName("营业收入"))
                .addStructureList(QotGetFinancialsStatements.FinancialFieldInfo.newBuilder()
                        .setFieldId(2).setDisplayName("净利润"))
                .addReportList(QotGetFinancialsStatements.FinancialReport.newBuilder()
                        .setDateTime(1_722_384_000L).setDateTimeStr("2024-07-31")
                        .setFiscalYear(2024).setPeriodText("2024/Q2").setCurrencyCode("USD")
                        .setAccountingStandards("美国会计准则").setAuditorReport("无保留意见")
                        .addItemList(QotGetFinancialsStatements.FinancialItem.newBuilder()
                                .setFieldId(1).setData(3.0e10).setYoy(122.4).setQoq(15.3))
                        .addItemList(QotGetFinancialsStatements.FinancialItem.newBuilder()
                                .setFieldId(2).setData(1.6e10)))
                .setNextKey("-1")
                .build();

        var reports = FutuFundamentals.toReports(new Instrument(Market.US, "NVDA"), FinancialStatement.INCOME, s2c);

        assertThat(reports).hasSize(1);
        var r = reports.getFirst();
        assertThat(r.periodEnd()).isEqualTo(LocalDate.of(2024, 7, 31));
        assertThat(r.periodText()).isEqualTo("2024/Q2");
        assertThat(r.currency()).isEqualTo("USD");
        assertThat(r.items()).extracting(FinancialReport.Item::name).containsExactly("营业收入", "净利润");
        assertThat(r.items().getFirst().yoy()).isEqualByComparingTo("122.4");
        assertThat(r.items().get(1).yoy()).as("没给同比就是空").isNull();
    }

    @Test
    void 公司简介按名值对保留_丢掉半条的项() {
        QotGetCompanyProfile.S2C s2c = QotGetCompanyProfile.S2C.newBuilder()
                .addItemList(QotGetCompanyProfile.CompanyLabItem.newBuilder().setName("公司名称").setValue("英伟达"))
                .addItemList(QotGetCompanyProfile.CompanyLabItem.newBuilder().setName("所属行业"))
                .build();

        var p = FutuFundamentals.toProfile(new Instrument(Market.US, "NVDA"), s2c);

        assertThat(p.fields()).containsExactly(java.util.Map.entry("公司名称", "英伟达"));
    }

    @Test
    void 领域报表类型映射到富途枚举() {
        assertThat(FutuFundamentals.statementType(FinancialStatement.INCOME))
                .isEqualTo(QotCommon.FinancialStatementsType.FinancialStatementsType_Income_VALUE);
        assertThat(FutuFundamentals.statementType(FinancialStatement.MAIN_INDEX))
                .isEqualTo(QotCommon.FinancialStatementsType.FinancialStatementsType_MainIndex_VALUE);
    }
}
