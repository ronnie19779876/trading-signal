package org.jdkxx.trader.ai.veto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 逐条核对模型给的证据：字段路径要在输入里存在，数值要与原值一致（相对 ±1%，兼容千分位、百分号、美元符号）。
 * 只做标记不删除——核对器自己也可能误判（兄弟项目实测过全角标点被判成捏造），结论保留原样、由否决规则只采信核对通过的。
 */
public final class EvidenceVerifier {

    static final double TOLERANCE = 0.01;
    private static final Pattern SEGMENT = Pattern.compile("([^.\\[\\]]+)|\\[(\\d+)]");
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?");

    private EvidenceVerifier() {
    }

    public enum Side {
        BULL, BEAR
    }

    public record Checked(Side side, VetoJudgment.Evidence evidence, boolean verified, String reason) {
    }

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

    /** 整串数字（严口径用）。 */
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
            boolean ok = actual == 0 ? Math.abs(c) < 1e-9 : Math.abs(c - actual) <= TOLERANCE * Math.abs(actual);
            return new Checked(side, e, ok, ok ? "一致" : "原值 " + node.asText() + "，引用 " + claimed);
        }
        if (node.isTextual() || node.isBoolean()) {
            String actual = node.asText().trim();
            // 数字回落必须用「整串就是一个数」的严口径。
            // 原先用 number()（抽第一个数字）：日期、期别这类以同一个年份开头的文本
            // 只要年份相同就判「一致」——"2026-09-17" 与 "2026-03-31" 都抽出 2026，
            // 核对器对这类字段形同虚设，AI 否决门槛（≥2 条核对通过的看空证据）被削弱
            // （2026-09-25 全项目审查发现）。
            Double a = strictNumber(actual);
            Double c = strictNumber(claimed);
            boolean ok = actual.equalsIgnoreCase(claimed)
                    || (a != null && c != null && Math.abs(a - c) <= TOLERANCE * Math.max(1e-9, Math.abs(a)));
            return new Checked(side, e, ok, ok ? "一致" : "原值 " + actual + "，引用 " + claimed);
        }
        return new Checked(side, e, false, "字段 " + e.field() + " 不是单个值");
    }

    /** a.b[0].c 形式的路径；下标越界或类型不符返回 null。 */
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
                int i = Integer.parseInt(m.group(2));
                cur = cur.isArray() && i < cur.size() ? cur.get(i) : null;
            }
        }
        return consumed == path.length() ? cur : null;
    }

    /** 去掉千分位、百分号、美元符号、全角符号后取第一个数字；取不到返回 null。 */
    static Double number(String s) {
        if (s == null) {
            return null;
        }
        String cleaned = s.replace(",", "").replace("，", "").replace("％", "%").replace("＄", "$").replace("−", "-");
        Matcher m = NUMBER.matcher(cleaned);
        return m.find() ? Double.valueOf(m.group()) : null;
    }

    /**
     * 严口径：去掉千分位、货币符号、百分号与空白之后，<b>整串</b>就是一个数才算数字。
     * {@code "2026-09-17"}、{@code "2026Q3"}、{@code "约 12 倍"} 都不算——
     * 它们交给字符串相等去判，别被「抽第一个数字」糊过去。
     */
    static Double strictNumber(String s) {
        if (s == null) {
            return null;
        }
        String cleaned = s.replace(",", "").replace("，", "").replace("％", "%").replace("＄", "$").replace("−", "-")
                .replace("%", "").replace("$", "").replace("￥", "").replace("¥", "").trim();
        return PURE_NUMBER.matcher(cleaned).matches() ? Double.valueOf(cleaned) : null;
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }
}
