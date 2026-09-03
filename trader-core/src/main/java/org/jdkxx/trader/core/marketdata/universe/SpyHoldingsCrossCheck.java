package org.jdkxx.trader.core.marketdata.universe;

import org.jdkxx.trader.core.marketdata.MarketDataProperties;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/**
 * 用 SSGA 每日公布的 SPY 持仓表交叉核对标普 500 成分股。只用于比对并报告差异，不作为数据源
 * （ETF 持仓与指数成分可能有短暂偏差，且表里含现金等非股票行）。
 */
public class SpyHoldingsCrossCheck {

    private final MarketDataProperties.Universe props;
    private final HttpClient http;

    public SpyHoldingsCrossCheck(MarketDataProperties.Universe props) {
        this(props, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build());
    }

    SpyHoldingsCrossCheck(MarketDataProperties.Universe props, HttpClient http) {
        this.props = props;
        this.http = http;
    }

    public Set<String> tickers() {
        try {
            HttpResponse<byte[]> rsp = http.send(HttpRequest.newBuilder(URI.create(props.spyHoldingsUrl()))
                    .header("User-Agent", props.userAgent()).timeout(Duration.ofSeconds(30)).GET().build(),
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
