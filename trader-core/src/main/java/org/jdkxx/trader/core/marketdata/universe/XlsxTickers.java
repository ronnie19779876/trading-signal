package org.jdkxx.trader.core.marketdata.universe;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从 xlsx（SSGA 的 SPY 持仓表）里取「Ticker」列：只解 sharedStrings 与第一张工作表，不引 POI。
 */
public final class XlsxTickers {

    private static final Pattern SI = Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL);
    private static final Pattern T = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL);
    private static final Pattern ROW = Pattern.compile("<row\\b[^>]*>(.*?)</row>", Pattern.DOTALL);
    private static final Pattern CELL = Pattern.compile("<c\\b([^>]*)>(.*?)</c>", Pattern.DOTALL);
    private static final Pattern V = Pattern.compile("<v>(.*?)</v>", Pattern.DOTALL);
    private static final Pattern TICKER = Pattern.compile("[A-Z][A-Z.\\-]{0,6}");

    private XlsxTickers() {
    }

    public static Set<String> tickers(byte[] xlsx, String headerName) {
        String shared = null;
        String sheet = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals("xl/sharedStrings.xml")) {
                    shared = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                } else if (e.getName().equals("xl/worksheets/sheet1.xml")) {
                    sheet = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("xlsx 解析失败：" + ex.getMessage(), ex);
        }
        if (shared == null || sheet == null) {
            throw new IllegalStateException("xlsx 缺少 sharedStrings 或 sheet1");
        }
        List<String> strings = new ArrayList<>();
        Matcher si = SI.matcher(shared);
        while (si.find()) {
            StringBuilder sb = new StringBuilder();
            Matcher t = T.matcher(si.group(1));
            while (t.find()) {
                sb.append(HtmlTables.unescape(t.group(1)));
            }
            strings.add(sb.toString());
        }
        Set<String> out = new LinkedHashSet<>();
        int tickerCol = -1;
        Matcher row = ROW.matcher(sheet);
        while (row.find()) {
            List<String[]> cells = new ArrayList<>();   // {col letters, value}
            Matcher c = CELL.matcher(row.group(1));
            while (c.find()) {
                String attrs = c.group(1);
                Matcher v = V.matcher(c.group(2));
                String value = v.find() ? v.group(1) : "";
                if (attrs.contains("t=\"s\"") && !value.isEmpty()) {
                    int idx = Integer.parseInt(value.trim());
                    value = idx < strings.size() ? strings.get(idx) : "";
                }
                Matcher ref = Pattern.compile("r=\"([A-Z]+)\\d+\"").matcher(attrs);
                cells.add(new String[] {ref.find() ? ref.group(1) : "", value.trim()});
            }
            if (tickerCol < 0) {
                for (int i = 0; i < cells.size(); i++) {
                    if (cells.get(i)[1].equalsIgnoreCase(headerName)) {
                        tickerCol = i;
                        break;
                    }
                }
                continue;
            }
            if (tickerCol < cells.size()) {
                String v = cells.get(tickerCol)[1].toUpperCase(Locale.ROOT);
                if (TICKER.matcher(v).matches()) {
                    out.add(v);
                }
            }
        }
        return out;
    }
}
