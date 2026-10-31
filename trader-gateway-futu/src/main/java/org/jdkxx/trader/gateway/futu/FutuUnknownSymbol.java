package org.jdkxx.trader.gateway.futu;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises "unknown stock" rejections in Futu's retMsg and extracts the symbols they name.
 * Futu-specific text parsing lives <b>only here</b>; core never touches the wording.
 *
 * <p>Observed messages (reproduced once each in the 2026-10-06 production log and a 2026-10-07
 * probe), shown here in English translation; the original Chinese phrase is the literal used by
 * {@code NAMED} and {@code looksUnknown}:
 *
 * <pre>
 * getSecuritySnapshot failed: unknown stock PSKY (retType=-1)
 * sub KL_Day ×90 failed: unknown stock PSKY (retType=-1)
 * </pre>
 *
 * <p><b>This parsing is a nice-to-have, not the deciding signal.</b> Failing to recognise a message
 * only loses one cross-check hint and recovery proceeds as usual: bad symbols are decided
 * structurally by {@code staticInfo}'s brokerId==0. So there is no need to cover every possible
 * Futu wording, and the pattern should not be loosened just to recognise more (loosening only
 * causes more ordinary words to be misreported as symbols).
 */
final class FutuUnknownSymbol {

  /**
   * "Unknown stock XXX" (the Chinese phrase in the pattern). The symbol uses the usual US-equity
   * character set, including dotted ones such as BRK.B.
   */
  private static final Pattern NAMED = Pattern.compile("未知股票\\s*([A-Za-z][A-Za-z0-9.\\-]{0,15})");

  private FutuUnknownSymbol() {}

  /** Whether retMsg looks like an "unknown stock" rejection. */
  static boolean looksUnknown(String retMsg) {
    return retMsg != null && retMsg.contains("未知股票");
  }

  /** Extracts the symbols named in retMsg (possibly none). */
  static Set<String> named(String retMsg) {
    Set<String> out = new LinkedHashSet<>();
    if (retMsg == null) {
      return out;
    }
    Matcher m = NAMED.matcher(retMsg);
    while (m.find()) {
      out.add(m.group(1).toUpperCase(java.util.Locale.ROOT));
    }
    return out;
  }
}
