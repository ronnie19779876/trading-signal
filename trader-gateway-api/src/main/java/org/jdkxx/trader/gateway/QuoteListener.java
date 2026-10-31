package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.Quote;

/** Real-time quote pushes. Callbacks run on the gateway's dispatch thread and must not block. */
@FunctionalInterface
public interface QuoteListener {

  void onQuote(Quote quote);
}
