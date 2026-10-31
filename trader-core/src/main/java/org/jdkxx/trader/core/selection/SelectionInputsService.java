package org.jdkxx.trader.core.selection;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jdkxx.trader.storage.selection.SelectionBackfillStateRepository;
import org.jdkxx.trader.storage.selection.SelectionCoverageRepository;
import org.jdkxx.trader.storage.selection.SelectionEarningsRepository;
import org.jdkxx.trader.storage.selection.SelectionPriceBarRepository;
import org.jdkxx.trader.storage.selection.SelectionPriceSeamRepository;
import org.jdkxx.trader.storage.selection.SelectionValuationRepository;

/**
 * Coverage report for quant selection inputs (the 6.1 acceptance in §25.9): read-only, changes no
 * data. Every item shows its denominator and samples, not just a pass / fail.
 */
public class SelectionInputsService {

  /**
   * Start of the announcement-date check: Futu only provides the latest 20 quarters, so there is
   * nothing further back.
   */
  static final int EARNINGS_LOOKBACK_YEARS = 4;

  static final int SAMPLES = 30;

  /**
   * @param missing number of names in the universe with no state at all (never fetched)
   */
  public record PartStatus(Map<String, Long> byStatus, long missing) {}

  public record Report(
      LocalDate asOf,
      int targets,
      Map<BackfillPart, PartStatus> parts,
      List<SelectionBackfillStateRepository.StateRow> failed,
      List<SelectionBackfillStateRepository.StateRow> empty,
      SelectionPriceBarRepository.Coverage priceBars,
      SelectionPriceSeamRepository.Summary seams,
      SelectionValuationRepository.Coverage valuation,
      SelectionValuationRepository.Agreement valuationVsSnapshot,
      SelectionEarningsRepository.Matching earnings,
      SelectionCoverageRepository.FinancialDepth financials) {}

  private final SelectionProperties props;
  private final SelectionTargets targets;
  private final SelectionBackfillStateRepository states;
  private final SelectionPriceBarRepository priceBars;
  private final SelectionPriceSeamRepository seams;
  private final SelectionValuationRepository valuations;
  private final SelectionEarningsRepository earnings;
  private final SelectionCoverageRepository coverage;
  private final Clock clock;
  private final ZoneId zone;

  public SelectionInputsService(
      SelectionProperties props,
      SelectionTargets targets,
      SelectionBackfillStateRepository states,
      SelectionPriceBarRepository priceBars,
      SelectionPriceSeamRepository seams,
      SelectionValuationRepository valuations,
      SelectionEarningsRepository earnings,
      SelectionCoverageRepository coverage,
      Clock clock,
      ZoneId zone) {
    this.props = props;
    this.targets = targets;
    this.states = states;
    this.priceBars = priceBars;
    this.seams = seams;
    this.valuations = valuations;
    this.earnings = earnings;
    this.coverage = coverage;
    this.clock = clock;
    this.zone = zone;
  }

  public Report report() {
    LocalDate today = LocalDate.now(clock.withZone(zone));
    int n = targets.all().size();
    Map<BackfillPart, Map<String, Long>> grouped = new EnumMap<>(BackfillPart.class);
    for (BackfillPart p : BackfillPart.values()) {
      grouped.put(p, new LinkedHashMap<>());
    }
    for (SelectionBackfillStateRepository.Count c : states.counts()) {
      grouped.get(BackfillPart.valueOf(c.part())).put(c.status(), c.n());
    }
    Map<BackfillPart, PartStatus> parts = new EnumMap<>(BackfillPart.class);
    grouped.forEach(
        (p, m) ->
            parts.put(
                p,
                new PartStatus(
                    m, Math.max(0, n - m.values().stream().mapToLong(Long::longValue).sum()))));
    // Quarterly reports must reach back to Q1 2016: a backtest starting in 2018 needs 8 quarters
    // before it for TTM growth
    LocalDate needBy = LocalDate.of(props.priceFrom().getYear(), 3, 31);
    return new Report(
        today,
        n,
        parts,
        states.findByStatus("FAILED", SAMPLES * 2),
        states.findByStatus("EMPTY", SAMPLES * 2),
        priceBars.coverage(),
        seams.summary(),
        valuations.coverage(),
        valuations.snapshotAgreement(SAMPLES),
        earnings.matching(today.minusYears(EARNINGS_LOOKBACK_YEARS), SAMPLES),
        coverage.financialDepth(needBy, SAMPLES));
  }
}
