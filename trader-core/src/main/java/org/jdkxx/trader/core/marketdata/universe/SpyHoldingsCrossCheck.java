package org.jdkxx.trader.core.marketdata.universe;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;

/**
 * Cross-checks the S&amp;P 500 constituents against the SPY holdings sheet SSGA publishes daily.
 * Only used to compare and report differences, never as a data source (ETF holdings can briefly
 * deviate from the index constituents, and the sheet contains cash and other non-equity lines).
 */
public class SpyHoldingsCrossCheck {

  private final MarketDataProperties.Universe props;
  private final HttpClient http;

  public SpyHoldingsCrossCheck(MarketDataProperties.Universe props) {
    this(
        props,
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build());
  }

  SpyHoldingsCrossCheck(MarketDataProperties.Universe props, HttpClient http) {
    this.props = props;
    this.http = http;
  }

  public Set<String> tickers() {
    try {
      HttpResponse<byte[]> rsp =
          http.send(
              HttpRequest.newBuilder(URI.create(props.spyHoldingsUrl()))
                  .header("User-Agent", props.userAgent())
                  .timeout(Duration.ofSeconds(30))
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      if (rsp.statusCode() != 200) {
        throw new IllegalStateException("SPY 持仓下载失败 HTTP " + rsp.statusCode());
      }
      return XlsxTickers.tickers(rsp.body(), "Ticker");
    } catch (IOException e) {
      throw new IllegalStateException("SPY 持仓下载失败：" + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("SPY 持仓下载被中断", e);
    }
  }
}
