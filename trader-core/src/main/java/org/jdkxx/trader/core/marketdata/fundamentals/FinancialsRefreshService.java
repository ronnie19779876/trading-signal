package org.jdkxx.trader.core.marketdata.fundamentals;

import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.CompanyProfile;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.CompanyProfileRepository;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 财报与公司简介作业：只做池与持仓，四类报表各取最近若干期。
 * 财报接口一次只能取一只、限频 30 次每 30 秒，全量 518 只要 35 分钟，绝大多数标的我们并不看，所以不做全量。
 *
 * <p>公司简介变化极慢，按 {@code profile-refresh-after} 到期才刷，避免每周白跑一遍。
 */
public class FinancialsRefreshService {

    private static final Logger log = LoggerFactory.getLogger(FinancialsRefreshService.class);
    private static final List<FinancialStatement> ALL = List.of(FinancialStatement.INCOME,
            FinancialStatement.BALANCE_SHEET, FinancialStatement.CASH_FLOW, FinancialStatement.MAIN_INDEX);

    private final MarketDataProperties props;
    private final UniverseScope scope;
    private final MarketDataGateway gateway;
    private final FinancialRepository financials;
    private final CompanyProfileRepository profiles;
    private final Clock clock;

    public FinancialsRefreshService(MarketDataProperties props, UniverseScope scope, MarketDataGateway gateway,
                                    FinancialRepository financials, CompanyProfileRepository profiles, Clock clock) {
        this.props = props;
        this.scope = scope;
        this.gateway = gateway;
        this.financials = financials;
        this.profiles = profiles;
        this.clock = clock;
    }

    public String run(JobContext ctx) {
        return refresh(scope.poolAndHoldings(), ctx);
    }

    /** 只刷指定标的，用于池新增成员时立刻补齐。 */
    public String refresh(List<InstrumentRow> targets, JobContext ctx) {
        int periods = props.fundamentals().financialPeriods();
        int okReports = 0;
        int failed = 0;
        int profileUpdated = 0;
        for (int i = 0; i < targets.size(); i++) {
            if (ctx.cancelled()) {
                ctx.partial("已取消");
                break;
            }
            InstrumentRow row = targets.get(i);
            Instrument instrument = new Instrument(row.market(), row.symbol());
            // ETF 没有财务报表，四次请求纯属浪费限频；简介照取（基金也有简介）
            for (FinancialStatement statement : row.type() == SecurityType.STOCK ? ALL : List.<FinancialStatement>of()) {
                try {
                    List<FinancialReport> reports = gateway.financials(instrument, statement, periods)
                            .get(30, TimeUnit.SECONDS);
                    okReports += financials.upsertAll(row.id(), reports);
                } catch (Exception e) {
                    failed++;
                    log.warn("{} 的 {} 取失败：{}", row.symbol(), statement, e.toString());
                }
            }
            if (refreshProfile(row, instrument)) {
                profileUpdated++;
            }
            ctx.progress("财报 " + (i + 1) + "/" + targets.size() + "（写入期数 " + okReports + "，失败 " + failed + "）");
        }
        if (failed > 0) {
            ctx.partial(failed + " 次报表请求失败");
        }
        return "财报刷新：目标 " + targets.size() + " 只，写入期数 " + okReports + "，失败 " + failed
                + "；公司简介更新 " + profileUpdated + " 只";
    }

    /** 简介到期才刷；取失败不影响财报，只记日志。 */
    private boolean refreshProfile(InstrumentRow row, Instrument instrument) {
        Duration after = props.fundamentals().profileRefreshAfter();
        Instant deadline = clock.instant().minus(after);
        if (profiles.fetchedAt(row.id()).filter(t -> t.isAfter(deadline)).isPresent()) {
            return false;
        }
        try {
            CompanyProfile profile = gateway.companyProfile(instrument).get(30, TimeUnit.SECONDS);
            if (profile.fields().isEmpty()) {
                return false;
            }
            profiles.save(row.id(), profile);
            return true;
        } catch (Exception e) {
            log.warn("{} 的公司简介取失败：{}", row.symbol(), e.toString());
            return false;
        }
    }
}
