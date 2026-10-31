package org.jdkxx.trader.gateway;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.jdkxx.trader.domain.FinancialReport;
import org.jdkxx.trader.domain.FinancialStatement;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.selection.EarningsEvent;
import org.jdkxx.trader.domain.selection.ValuationMetric;
import org.jdkxx.trader.domain.selection.ValuationPoint;
import org.jdkxx.trader.domain.selection.ValuationRange;

/**
 * Fundamental inputs for quant selection (ARCHITECTURE §25.6; implemented by Futu). Read-only; none
 * of these use subscription quota or history-bar quota. It is a separate port rather than additions
 * to {@link MarketDataGateway} because quant selection is an independent feature.
 */
public interface SelectionDataGateway {

  /**
   * Quarterly reports only (no annual reports mixed in), one instrument per call, {@code periods}
   * capped at 50 (measured: one page of 50 quarters reaches back to about 2014).
   */
  CompletableFuture<List<FinancialReport>> quarterlyFinancials(
      Instrument instrument, FinancialStatement statement, int periods);

  /** Daily valuation history, ordered by date from oldest to newest. */
  CompletableFuture<List<ValuationPoint>> valuationHistory(
      Instrument instrument, ValuationMetric metric, ValuationRange range);

  /**
   * Earnings announcement dates (measured: the latest 20 quarters, including future scheduled
   * dates).
   */
  CompletableFuture<List<EarningsEvent>> earningsHistory(Instrument instrument);
}
