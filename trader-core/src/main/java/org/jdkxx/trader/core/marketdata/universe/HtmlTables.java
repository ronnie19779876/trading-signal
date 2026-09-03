package org.jdkxx.trader.core.marketdata.universe;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 够用的 HTML 表格提取：定位 &lt;table&gt;，逐行取 th/td 文本（去标签、去脚注、解实体、压空白）。
 * 不引第三方 HTML 库：Wikipedia 的成分股表结构简单，且我们只信任表头名。
 */
public final class HtmlTables {

    private static final Pattern TABLE = Pattern.compile("<table\\b([^>]*)>(.*?)</table>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ROW = Pattern.compile("<tr\\b[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern CELL = Pattern.compile("<t[hd]\\b[^>]*>(.*?)</t[hd]>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern FOOTNOTE = Pattern.compile("\\[[^\\]]{1,6}\\]");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|nbsp|apos|ndash|mdash);");

    private HtmlTables() {
    }

    /** 先按 id 找，找不到就取第一张表头里含任一关键字的表；都没有返回空列表。 */
    public static List<List<String>> find(String html, String id, String... headerKeywords) {
        Matcher m = TABLE.matcher(html);
        List<List<String>> fallback = null;
        while (m.find()) {
            List<List<String>> rows = rows(m.group(2));
            if (rows.isEmpty()) {
                continue;
            }
            if (id != null && m.group(1).contains("id=\"" + id + "\"")) {
                return rows;
            }
            if (fallback == null && matchesHeader(rows.get(0), headerKeywords)) {
                fallback = rows;
            }
        }
        return fallback == null ? List.of() : fallback;
    }

    private static boolean matchesHeader(List<String> header, String... keywords) {
        for (String h : header) {
            for (String k : keywords) {
                if (h.equalsIgnoreCase(k)) {
                    return true;
                }
            }
        }
        return false;
    }

    static List<List<String>> rows(String tableHtml) {
        List<List<String>> rows = new ArrayList<>();
        Matcher r = ROW.matcher(tableHtml);
        while (r.find()) {
            List<String> cells = new ArrayList<>();
            Matcher c = CELL.matcher(r.group(1));
            while (c.find()) {
                cells.add(text(c.group(1)));
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        return rows;
    }

    static String text(String cellHtml) {
        String s = TAG.matcher(cellHtml).replaceAll(" ");
        s = FOOTNOTE.matcher(s).replaceAll("");
        s = unescape(s);
        return s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    static String unescape(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String e = m.group(1);
            String rep = switch (e) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                case "ndash" -> "–";
                case "mdash" -> "—";
                default -> {
                    try {
                        int cp = e.startsWith("#x") || e.startsWith("#X") ? Integer.parseInt(e.substring(2), 16) : Integer.parseInt(e.substring(1));
                        yield new String(Character.toChars(cp));
                    } catch (RuntimeException ex) {
                        yield m.group(0);
                    }
                }
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
