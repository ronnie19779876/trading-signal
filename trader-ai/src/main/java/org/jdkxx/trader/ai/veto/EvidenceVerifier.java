package org.jdkxx.trader.ai.veto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the model's evidence item by item: the field path must exist in the input and the value
 * must match the original (within ±1% relative, tolerating thousands separators, percent signs and
 * dollar signs). It only marks items and never deletes them: the verifier itself can be wrong (in a
 * sibling project full-width punctuation was measured to be flagged as fabrication), so the
 * conclusion is kept intact and the veto rule only trusts the items that passed.
 *
 * <p>"Verified" means only that the quoted value matches the input. Whether the evidence actually
 * supports the stance is not checked here.
 */
public final class EvidenceVerifier {

  static final double TOLERANCE = 0.01;
  private static final Pattern SEGMENT = Pattern.compile("([^.\\[\\]]+)|\\[(\\d+)]");
  private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?");

  private EvidenceVerifier() {}

  public enum Side {
    BULL,
    BEAR
  }

  public record Checked(
      Side side, VetoJudgment.Evidence evidence, boolean verified, String reason) {}

  public static List<Checked> check(JsonNode input, VetoJudgment judgment) {
    List<Checked> out = new ArrayList<>();
    for (VetoJudgment.Evidence e : nonNull(judgment.bullEvidence())) {
      out.add(checkOne(input, Side.BULL, e));
    }
    for (VetoJudgment.Evidence e : nonNull(judgment.bearEvidence())) {
      out.add(checkOne(input, Side.BEAR, e));
    }
    return out;
  }

  /** The whole string is one number (used by the strict check). */
  private static final Pattern PURE_NUMBER = Pattern.compile("[+-]?\\d+(?:\\.\\d+)?");

  static Checked checkOne(JsonNode input, Side side, VetoJudgment.Evidence e) {
    if (e == null || e.field() == null || e.field().isBlank()) {
      return new Checked(side, e, false, "没有字段路径");
    }
    JsonNode node = resolve(input, e.field().trim());
    if (node == null || node.isMissingNode()) {
      return new Checked(side, e, false, "输入里没有字段 " + e.field());
    }
    if (node.isNull()) {
      return new Checked(side, e, false, "字段 " + e.field() + " 为空");
    }
    String claimed = e.value() == null ? "" : e.value().trim();
    if (node.isNumber()) {
      Double c = number(claimed);
      if (c == null) {
        return new Checked(side, e, false, "引用值不是数字：" + claimed);
      }
      double actual = node.asDouble();
      boolean ok =
          actual == 0 ? Math.abs(c) < 1e-9 : Math.abs(c - actual) <= TOLERANCE * Math.abs(actual);
      return new Checked(side, e, ok, ok ? "一致" : "原值 " + node.asText() + "，引用 " + claimed);
    }
    if (node.isTextual() || node.isBoolean()) {
      String actual = node.asText().trim();
      // The numeric fallback must use the strict "the whole string is one number" check.
      // It used to use number() (extract the first number): text such as dates and period labels
      // that start with the same year was judged "matching" as long as the year was the same, since
      // "2026-09-17" and "2026-03-31" both yield 2026. The verifier was useless for such fields and
      // the AI veto threshold (at least 2 verified bearish evidence items) was weakened (found in
      // the 2026-09-25 full-project review).
      Double a = strictNumber(actual);
      Double c = strictNumber(claimed);
      boolean ok =
          actual.equalsIgnoreCase(claimed)
              || (a != null
                  && c != null
                  && Math.abs(a - c) <= TOLERANCE * Math.max(1e-9, Math.abs(a)));
      return new Checked(side, e, ok, ok ? "一致" : "原值 " + actual + "，引用 " + claimed);
    }
    return new Checked(side, e, false, "字段 " + e.field() + " 不是单个值");
  }

  /**
   * Resolves a path of the form a.b[0].c; returns null on an out-of-range index (including one too
   * large for an int) or a type mismatch. Every character of the path must be consumed by a segment
   * or a single separating dot, otherwise the path is rejected.
   */
  static JsonNode resolve(JsonNode root, String path) {
    JsonNode cur = root;
    Matcher m = SEGMENT.matcher(path);
    int consumed = 0;
    while (m.find()) {
      if (cur == null) {
        return null;
      }
      String gap = path.substring(consumed, m.start());
      if (!gap.isEmpty() && !gap.equals(".")) {
        return null;
      }
      consumed = m.end();
      if (m.group(1) != null) {
        cur = cur.isObject() ? cur.get(m.group(1)) : null;
      } else {
        // a hallucinated index too large for an int is simply out of range; before 3.5.2 the
        // NumberFormatException escaped and the whole paid call was recorded as INVALID
        int i;
        try {
          i = Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
          return null;
        }
        cur = cur.isArray() && i < cur.size() ? cur.get(i) : null;
      }
    }
    return consumed == path.length() ? cur : null;
  }

  /**
   * Removes thousands separators, percent and dollar signs and full-width symbols, then takes the
   * first number; returns null if there is none.
   */
  static Double number(String s) {
    if (s == null) {
      return null;
    }
    String cleaned =
        s.replace(",", "").replace("，", "").replace("％", "%").replace("＄", "$").replace("−", "-");
    Matcher m = NUMBER.matcher(cleaned);
    return m.find() ? Double.valueOf(m.group()) : null;
  }

  /**
   * Strict check: after removing thousands separators, currency signs, percent signs and
   * whitespace, the <b>whole string</b> must be one number to count as numeric. {@code
   * "2026-09-17"}, {@code "2026Q3"} and "about 12x" (written in Chinese in practice) do not count:
   * they are left to string equality instead of being glossed over by "extract the first number".
   */
  static Double strictNumber(String s) {
    if (s == null) {
      return null;
    }
    String cleaned =
        s.replace(",", "")
            .replace("，", "")
            .replace("％", "%")
            .replace("＄", "$")
            .replace("−", "-")
            .replace("%", "")
            .replace("$", "")
            .replace("￥", "")
            .replace("¥", "")
            .trim();
    return PURE_NUMBER.matcher(cleaned).matches() ? Double.valueOf(cleaned) : null;
  }

  private static <T> List<T> nonNull(List<T> list) {
    return list == null ? List.of() : list;
  }
}
