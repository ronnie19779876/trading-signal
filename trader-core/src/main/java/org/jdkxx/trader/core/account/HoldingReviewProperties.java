package org.jdkxx.trader.core.account;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Holding risk panel ({@code trader.holding-review.*}, 3.5.0, ARCHITECTURE §29). Defaults are the
 * values of the plan the user approved on 2026-10-30.
 *
 * @param windowDays earnings warning window: earnings within this many upcoming trading days are
 *     flagged
 * @param stressMultiple stress scenario = historical maximum drop x this multiple (GOOG fell 12.3%
 *     on 2026-10-29, 1.28 times the largest of the previous 19 reactions)
 * @param lossBudgetPct the most the user is willing to lose on one instrument through earnings, as
 *     a percent of net liquidation (1.2 means 1.2%); <b>set by the user; when unset there is no
 *     over-budget check and no position sizing</b>
 * @param smaShort short moving average
 * @param breakConfirmDays this many consecutive closes below the long average count as a confirmed
 *     break
 * @param deathCross whether the short average below the long average is a review condition
 * @param includePool also include pool members not held (no amounts, only the historical
 *     distribution and position sizing), as a reference before adding
 */
@ConfigurationProperties(prefix = "trader.holding-review")
public record HoldingReviewProperties(
    @DefaultValue("10") int windowDays,
    @DefaultValue("1.5") double stressMultiple,
    BigDecimal lossBudgetPct,
    @DefaultValue("50") int smaShort,
    @DefaultValue("3") int breakConfirmDays,
    @DefaultValue("true") boolean deathCross,
    @DefaultValue("true") boolean includePool) {}
