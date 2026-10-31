package org.jdkxx.trader.core.marketdata.universe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jdkxx.trader.domain.IndexCode;

/**
 * Manual import fallback: one row per line, {@code
 * index_code,symbol[,name[,sector[,sub_industry]]]}; lines starting with # are comments, and a
 * header row is optional. Plain comma split, so quoted fields with commas are not supported.
 */
public final class CsvUniverse {

  private CsvUniverse() {}

  public static List<ConstituentEntry> parse(String text) {
    List<ConstituentEntry> out = new ArrayList<>();
    for (String raw : text.split("\\R")) {
      String line = raw.trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      String[] p = line.split(",", -1);
      if (p.length < 2) {
        throw new IllegalArgumentException("CSV 行至少要有 index_code,symbol：" + line);
      }
      String idx = p[0].trim().toUpperCase(Locale.ROOT);
      if (idx.equals("INDEX_CODE") || idx.equals("INDEX")) {
        continue; // header row
      }
      IndexCode index;
      try {
        index = IndexCode.valueOf(idx);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("未知指数 " + p[0] + "（可选 SP500 / NDX100）：" + line);
      }
      String symbol = WikipediaUniverseSource.normalize(p[1]);
      if (symbol.isEmpty()) {
        throw new IllegalArgumentException("symbol 为空：" + line);
      }
      out.add(
          new ConstituentEntry(
              index,
              symbol,
              p.length > 2 ? blankToNull(p[2]) : null,
              p.length > 3 ? blankToNull(p[3]) : null,
              p.length > 4 ? blankToNull(p[4]) : null,
              null));
    }
    return out;
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s.trim();
  }
}
