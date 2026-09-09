package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetCompanyProfile;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.futu.openapi.pb.QotGetSecuritySnapshot;
import org.jdkxx.trader.domain.CompanyProfile;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.ValuationSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 富途基本面数据 → 领域。
 * <p>个股走 equityExData（市盈率、市净率、每股收益…），ETF 走 trustExData（净值、溢价、资产规模），
 * 两者字段不重叠：ETF 的资产规模记进 netAsset、份额记进 outstandingShares、股息率记进 dividendYieldTtm，
 * 净值与溢价记进 navPerShare / premium。
 *
 * <p><b>取值口径以实测为准</b>（2026-09-09 对 400 只成分股 + 若干亏损股实跑）：
 * <ul>
 *   <li>估值字段在 proto 里是 required，{@code has*()} 恒为 true，判空没有意义；</li>
 *   <li>亏损股的市盈率、市净率是<b>负数</b>而不是 0 或缺失（实测 INTC -49.99、LCID -0.34），负值要原样保留；</li>
 *   <li>400 只里市盈率、市值没有一个是 0，说明 0 不是"无数据"的哨兵，直接取值即可；</li>
 *   <li>唯一的例外是 Trust 类的净值：实测富途对标普 500 里的 25 只 REITs 都不给净值（返回 0），
 *       只有真 ETF（SPY）有，这种 0 要转成 null，溢价由净值算出，净值缺失时一并作废；</li>
 *   <li>股息为 0 是真实的"不分红"（400 只里 86 只如此），保留 0，不要转 null。</li>
 * </ul>
 */
public final class FutuFundamentals {

    private FutuFundamentals() {
    }

    public static List<ValuationSnapshot> toSnapshots(List<QotGetSecuritySnapshot.Snapshot> list) {
        List<ValuationSnapshot> out = new ArrayList<>(list.size());
        for (QotGetSecuritySnapshot.Snapshot s : list) {
            QotGetSecuritySnapshot.SnapshotBasicData b = s.getBasic();
            Instrument instrument = new Instrument(FutuStatics.market(b.getSecurity().getMarket()), b.getSecurity().getCode());
            Instant asOf = b.getUpdateTimestamp() > 0
                    ? Instant.ofEpochSecond((long) b.getUpdateTimestamp())
                    : Instant.now();
            BigDecimal turnoverRate = b.hasTurnoverRate() ? dec(b.getTurnoverRate()) : null;

            if (s.hasEquityExData()) {
                QotGetSecuritySnapshot.EquitySnapshotExData e = s.getEquityExData();
                out.add(new ValuationSnapshot(instrument, asOf, b.getIsSuspend(),
                        dec(e.getIssuedMarketVal()),
                        dec(e.getOutstandingMarketVal()),
                        e.getIssuedShares(),
                        e.getOutstandingShares(),
                        dec(e.getPeRate()),
                        dec(e.getPeTTMRate()),
                        dec(e.getPbRate()),
                        dec(e.getEarningsPershare()),
                        dec(e.getNetAssetPershare()),
                        dec(e.getNetAsset()),
                        dec(e.getNetProfit()),
                        dec(e.getDividendTTM()),
                        dec(e.getDividendRatioTTM()),
                        turnoverRate, null, null));
            } else if (s.hasTrustExData()) {
                QotGetSecuritySnapshot.TrustSnapshotExData t = s.getTrustExData();
                boolean hasNav = t.getNetAssetValue() != 0;   // 富途对 REITs 不给净值，用 0 占位；真 ETF 才有
                out.add(new ValuationSnapshot(instrument, asOf, b.getIsSuspend(),
                        null, null, null,
                        t.getOutstandingUnits(),
                        null, null, null, null, null,
                        dec(t.getAum()),
                        null, null,
                        dec(t.getDividendYield()),
                        turnoverRate,
                        hasNav ? dec(t.getNetAssetValue()) : null,
                        hasNav ? dec(t.getPremium()) : null));
            } else {
                // 指数、窝轮、期权等没有估值口径，只记标识与停牌，交由上层决定要不要落库
                out.add(new ValuationSnapshot(instrument, asOf, b.getIsSuspend(),
                        null, null, null, null, null, null, null, null, null, null, null, null, null,
                        turnoverRate, null, null));
            }
        }
        return out;
    }

    public static List<FinancialReport> toReports(Instrument instrument, FinancialStatement statement,
                                                  QotGetFinancialsStatements.S2C s2c) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (QotGetFinancialsStatements.FinancialFieldInfo f : s2c.getStructureListList()) {
            names.put(f.getFieldId(), f.getDisplayName());
        }
        List<FinancialReport> out = new ArrayList<>();
        for (QotGetFinancialsStatements.FinancialReport r : s2c.getReportListList()) {
            List<FinancialReport.Item> items = new ArrayList<>(r.getItemListCount());
            for (QotGetFinancialsStatements.FinancialItem i : r.getItemListList()) {
                items.add(new FinancialReport.Item(i.getFieldId(), names.get(i.getFieldId()),
                        dec(i.hasData(), i.getData()), dec(i.hasYoy(), i.getYoy()), dec(i.hasQoq(), i.getQoq())));
            }
            out.add(new FinancialReport(instrument, statement, periodEnd(r), r.getFiscalYear(),
                    emptyToNull(r.getPeriodText()), emptyToNull(r.getCurrencyCode()),
                    emptyToNull(r.getAccountingStandards()), emptyToNull(r.getAuditorReport()), List.copyOf(items)));
        }
        return out;
    }

    public static CompanyProfile toProfile(Instrument instrument, QotGetCompanyProfile.S2C s2c) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (QotGetCompanyProfile.CompanyLabItem i : s2c.getItemListList()) {
            if (i.hasName() && i.hasValue()) {
                fields.put(i.getName(), i.getValue());
            }
        }
        return new CompanyProfile(instrument, Map.copyOf(fields));
    }

    /** 领域枚举 → 富途报表类型。 */
    public static int statementType(FinancialStatement s) {
        return switch (s) {
            case INCOME -> QotCommon.FinancialStatementsType.FinancialStatementsType_Income_VALUE;
            case BALANCE_SHEET -> QotCommon.FinancialStatementsType.FinancialStatementsType_BalanceSheet_VALUE;
            case CASH_FLOW -> QotCommon.FinancialStatementsType.FinancialStatementsType_CashFlow_VALUE;
            case MAIN_INDEX -> QotCommon.FinancialStatementsType.FinancialStatementsType_MainIndex_VALUE;
        };
    }

    static LocalDate periodEnd(QotGetFinancialsStatements.FinancialReport r) {
        String s = r.getDateTimeStr();
        if (s != null && s.length() >= 10) {
            try {
                return LocalDate.parse(s.substring(0, 10));
            } catch (RuntimeException ignored) {
                // 落到时间戳
            }
        }
        return r.getDateTime() > 0 ? LocalDate.ofInstant(Instant.ofEpochSecond(r.getDateTime()), java.time.ZoneOffset.UTC) : null;
    }

    private static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v);
    }

    private static BigDecimal dec(boolean present, double v) {
        return present ? BigDecimal.valueOf(v) : null;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
