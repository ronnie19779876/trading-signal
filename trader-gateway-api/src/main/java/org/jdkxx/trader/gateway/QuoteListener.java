package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.Quote;

/** 实时报价推送。回调在网关的 dispatch 线程上，不得阻塞。 */
@FunctionalInterface
public interface QuoteListener {

    void onQuote(Quote quote);
}
