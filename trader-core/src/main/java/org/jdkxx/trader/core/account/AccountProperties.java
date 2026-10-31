package org.jdkxx.trader.core.account;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Account and positions configuration ({@code trader.account.*}, phase 3).
 *
 * <p>keySecret and cashEquivalents are sensitive and <b>are not written in the committed yml</b>:
 * locally they go into {@code config/secrets.yml}, on the server into the environment variables
 * {@code TRADER_ACCOUNT_KEY_SECRET / TRADER_ACCOUNT_CASH_EQUIVALENTS}.
 *
 * @param enabled whether the scheduled account snapshot is wired (manual endpoints are not
 *     affected)
 * @param snapshotCron daily snapshot time (US/Eastern, after the increment and valuation)
 * @param keySecret secret for the account-number HMAC (at least 16 characters)
 * @param cashEquivalents symbols the user holds for cash management: counted in market value, but
 *     never added to the pool and excluded from the position-set check
 * @param valueTolerance market-value reconciliation tolerance (ratio); more than 5x is FAIL
 * @param identityTolerance funds-identity tolerance (amount)
 */
@ConfigurationProperties(prefix = "trader.account")
public record AccountProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("0 0 18 * * MON-FRI") String snapshotCron,
    String keySecret,
    @DefaultValue List<String> cashEquivalents,
    @DefaultValue("0.002") BigDecimal valueTolerance,
    @DefaultValue("1") BigDecimal identityTolerance) {}
