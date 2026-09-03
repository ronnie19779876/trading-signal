package org.jdkxx.trader.core.marketdata.universe;

import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.domain.IndexCode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 从 Wikipedia 的成分股表读取：「List of S&amp;P 500 companies」（表头 Symbol / Security / GICS Sector / GICS Sub-Industry / Date added）
 * 与「List of NASDAQ-100 companies」（Ticker / Company / ICB Industry / ICB Subsector）。按表头名取列，不依赖列序。
 */
public class WikipediaUniverseSource implements UniverseSource {

    private final MarketDataProperties.Universe props;
    private final HttpClient http;

    public WikipediaUniverseSource(MarketDataProperties.Universe props) {
        this(props, HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build());
    }

    WikipediaUniverseSource(MarketDataProperties.Universe props, HttpClient http) {
        this.props = props;
        this.http = http;
    }

    @Override
    public String name() {
        return "WIKIPEDIA";
    }

    @Override
    public List<ConstituentEntry> fetch(IndexCode index) {
        String url = index == IndexCode.SP500 ? props.wikipediaSp500Url() : props.wikipediaNdx100Url();
        return parse(index, get(url));
    }

    String get(String url) {
        try {
            HttpResponse<String> rsp = http.send(HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", props.userAgent()).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (rsp.statusCode() != 200) {
                throw new IllegalStateException("下载失败 HTTP " + rsp.statusCode() + "：" + url);
            }
            return rsp.body();
        } catch (IOException e) {
            throw new IllegalStateException("下载失败：" + url + "（" + e.getMessage() + "）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("下载被中断：" + url, e);
        }
    }

    static List<ConstituentEntry> parse(IndexCode index, String html) {
        List<List<String>> rows = HtmlTables.find(html, "constituents", "Symbol", "Ticker");
        if (rows.size() < 2) {
            throw new IllegalStateException(index + " 成分股表没找到（页面结构可能变了）");
        }
        List<String> header = rows.get(0);
        int symbol = col(header, "Symbol", "Ticker");
        int name = col(header, "Security", "Company");
        int sector = colStartsWith(header, "GICS Sector", "ICB Industry", "Sector");
        int sub = colStartsWith(header, "GICS Sub-Industry", "ICB Subsector", "Sub-Industry");
        int added = colStartsWith(header, "Date added", "Date first added");
        if (symbol < 0) {
            throw new IllegalStateException(index + " 成分股表没有 Symbol/Ticker 列：" + header);
        }
        List<ConstituentEntry> out = new ArrayList<>();
        for (List<String> r : rows.subList(1, rows.size())) {
            if (r.size() <= symbol) {
                continue;
            }
            String sym = normalize(r.get(symbol));
            if (sym.isEmpty()) {
                continue;
            }
            out.add(new ConstituentEntry(index, sym, cell(r, name), cell(r, sector), cell(r, sub), date(cell(r, added))));
        }
        if (out.size() < 50) {
            throw new IllegalStateException(index + " 只解析出 " + out.size() + " 行，不可信");
        }
        return out;
    }

    static String normalize(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT).replace('‑', '-').replace('–', '-');
    }

    private static String cell(List<String> row, int i) {
        return i < 0 || i >= row.size() ? null : row.get(i);
    }

    private static LocalDate date(String s) {
        if (s == null || s.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(s.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int col(List<String> header, String... names) {
        for (int i = 0; i < header.size(); i++) {
            for (String n : names) {
                if (header.get(i).equalsIgnoreCase(n)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int colStartsWith(List<String> header, String... prefixes) {
        for (int i = 0; i < header.size(); i++) {
            for (String p : prefixes) {
                if (header.get(i).toLowerCase(Locale.ROOT).startsWith(p.toLowerCase(Locale.ROOT))) {
                    return i;
                }
            }
        }
        return -1;
    }
}
