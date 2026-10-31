package org.jdkxx.trader.core.marketdata.fundamentals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.jobs.JobContext;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.CompanyProfile;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.CompanyProfileRepository;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Financial statements and company profile job: pool and holdings only, the most recent periods of
 * each of the four statement types. The financials API takes one instrument per call with a rate
 * limit of 30 per 30 seconds; the full 518 instruments take 35 minutes and we do not look at most
 * of them, so there is no full run by default.
 *
 * <p>Company profiles change very slowly, so they are refreshed only after {@code
 * profile-refresh-after} expires, to avoid a pointless weekly pass.
 *
 * <p>Requests go through the gateway's rate limiter (configured at 25/30s, below Futu's 30/30s,
 * because sending at exactly 30/30s got rejected), so the job thread mostly waits on the limiter.
 */
public class FinancialsRefreshService {

  private static final Logger log = LoggerFactory.getLogger(FinancialsRefreshService.class);
  private static final List<FinancialStatement> ALL =
      List.of(
          FinancialStatement.INCOME,
          FinancialStatement.BALANCE_SHEET,
          FinancialStatement.CASH_FLOW,
          FinancialStatement.MAIN_INDEX);

  private final MarketDataProperties props;
  private final UniverseScope scope;
  private final MarketDataGateway gateway;
  private final FinancialRepository financials;
  private final CompanyProfileRepository profiles;
  private final Clock clock;

  public FinancialsRefreshService(
      MarketDataProperties props,
      UniverseScope scope,
      MarketDataGateway gateway,
      FinancialRepository financials,
      CompanyProfileRepository profiles,
      Clock clock) {
    this.props = props;
    this.scope = scope;
    this.gateway = gateway;
    this.financials = financials;
    this.profiles = profiles;
    this.clock = clock;
  }

  public String run(JobContext ctx) {
    return refresh(scope.poolAndHoldings(), true, ctx);
  }

  /**
   * Full run: constituents ∪ pool ∪ holdings. Four statement types per instrument at 25/30s; 518
   * instruments take about 41 minutes, occupying the job thread the whole time, so do not schedule
   * it next to rotation jobs. Company profiles are not fetched here (another 518 requests would add
   * more than ten minutes).
   */
  public String runAll(JobContext ctx) {
    Map<Long, InstrumentRow> all = new LinkedHashMap<>();
    scope.universe().forEach(r -> all.put(r.id(), r));
    scope.poolAndHoldings().forEach(r -> all.put(r.id(), r));
    return refresh(List.copyOf(all.values()), false, ctx);
  }

  /** Refreshes only the given instruments; used to fill in a new pool member right away. */
  public String refresh(List<InstrumentRow> targets, JobContext ctx) {
    return refresh(targets, true, ctx);
  }

  private String refresh(List<InstrumentRow> targets, boolean withProfiles, JobContext ctx) {
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
      // Do not skip by type: Futu classifies US REITs as Trust too (which we map to ETF), and they
      // do have financial statements. A real fund returns an empty list; one wasted request is
      // cheaper than missing 25 REITs.
      for (FinancialStatement statement : ALL) {
        try {
          List<FinancialReport> reports =
              gateway.financials(instrument, statement, periods).get(30, TimeUnit.SECONDS);
          okReports += financials.upsertAll(row.id(), reports);
        } catch (Exception e) {
          failed++;
          log.warn("{} 的 {} 取失败：{}", row.symbol(), statement, e.toString());
        }
      }
      if (withProfiles && refreshProfile(row, instrument)) {
        profileUpdated++;
      }
      ctx.progress(
          "财报 " + (i + 1) + "/" + targets.size() + "（写入期数 " + okReports + "，失败 " + failed + "）");
    }
    if (failed > 0) {
      ctx.partial(failed + " 次报表请求失败");
    }
    return "财报刷新：目标 "
        + targets.size()
        + " 只，写入期数 "
        + okReports
        + "，失败 "
        + failed
        + (withProfiles ? "；公司简介更新 " + profileUpdated + " 只" : "；未取公司简介");
  }

  /**
   * Refreshes the profile only when it has expired; a failure does not affect the statements and is
   * only logged.
   */
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
