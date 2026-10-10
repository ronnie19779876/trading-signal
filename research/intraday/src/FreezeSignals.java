import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 第 0 期第 2 步 · 冻结信号清单（ARCHITECTURE §24.6）。
 *
 * <p>对开发实例逐只调回放（sentinel-v1、止损 2.0×ATR、不减半仓），回放从 2023-10-02 起跑、只留信号日 ∈ [2024-01-02, 2026-06-30]。
 * 成分股按代码排序、每 3 只取 1 只标为 H1 样本。输出 data/signals.csv 与 data/signals-freeze.txt（条数、sha256、失败的标的）。
 */
public final class FreezeSignals {

    /** 缺省即主样本的预登记参数；附带报告（深度标的）用 -Ddepth=HIST20Y -DreplayFrom=… -DwindowFrom=… -Dout=… 覆盖。 */
    static final LocalDate REPLAY_FROM = LocalDate.parse(System.getProperty("replayFrom", "2023-10-02"));
    static final LocalDate WINDOW_FROM = LocalDate.parse(System.getProperty("windowFrom", "2024-01-02"));
    static final LocalDate WINDOW_TO = LocalDate.parse(System.getProperty("windowTo", "2026-06-30"));
    static final String DEPTH = System.getProperty("depth");
    static final String OUT = System.getProperty("out", "signals");

    public static void main(String[] args) throws Exception {
        List<String> symbols = new ArrayList<>();
        for (JsonNode u : Lab.get("/api/universe")) {
            if (u.path("indexes").size() > 0 && "RESOLVED".equals(u.path("resolveStatus").asText())
                    && (DEPTH == null || DEPTH.equals(u.path("depth").asText()))) {
                symbols.add(u.path("symbol").asText());
            }
        }
        symbols.sort(null);

        List<List<?>> rows = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        Map<String, Integer> byYear = new TreeMap<>();
        Map<String, Integer> byReason = new TreeMap<>();
        int closeMismatch = 0;
        for (int i = 0; i < symbols.size(); i++) {
            String s = symbols.get(i);
            boolean h1 = i % 3 == 0;
            JsonNode replay;
            try {
                replay = Lab.get("/api/signals/replay/" + s + "?from=" + REPLAY_FROM + "&to=" + WINDOW_TO + "&trades=true");
            } catch (Exception e) {
                failures.add(s + "：" + e.getMessage().replace('\n', ' '));
                continue;
            }
            Map<String, JsonNode> days = new TreeMap<>();
            for (JsonNode d : replay.path("days")) {
                days.put(d.path("date").asText(), d);
            }
            for (JsonNode t : replay.path("trades")) {
                LocalDate signal = LocalDate.parse(t.path("signalDate").asText());
                if (signal.isBefore(WINDOW_FROM) || signal.isAfter(WINDOW_TO)) {
                    continue;
                }
                double stop = t.path("stop").asDouble();
                double plus = t.path("plusOneR").asDouble();
                double close = (plus + stop) / 2;
                double risk = (plus - stop) / 2;
                JsonNode day = days.get(signal.toString());
                if (day == null || Math.abs(day.path("close").asDouble() - close) > 1e-6 * close) {
                    closeMismatch++;
                }
                rows.add(java.util.Arrays.asList(s, h1, signal, t.path("entryDate").asText(), close, stop, plus, risk,
                        t.path("entry").asDouble(), t.path("touchedPlusOneR").asBoolean(),
                        text(t, "exitDate"), text(t, "exit"), t.path("reason").asText(), text(t, "r"),
                        t.path("mfeR").asDouble(), t.path("maeR").asDouble(), t.path("barsHeld").asInt(),
                        t.path("lastCloseDate").asText()));
                byYear.merge(signal.toString().substring(0, 4), 1, Integer::sum);
                byReason.merge(t.path("reason").asText(), 1, Integer::sum);
            }
            if ((i + 1) % 50 == 0) {
                System.out.printf("%d/%d 只，累计 %d 条%n", i + 1, symbols.size(), rows.size());
            }
        }

        var csv = Lab.DATA.resolve(OUT + ".csv");
        Lab.writeCsv(csv, List.of("symbol", "h1", "signalDate", "entryDate", "close", "stop", "plusOneR", "risk", "entry",
                "touched", "exitDate", "exit", "reason", "r", "mfeR", "maeR", "barsHeld", "lastCloseDate"), rows);
        long h1 = rows.stream().filter(r -> (Boolean) r.get(1)).count();
        String meta = String.join("\n",
                "frozenAt=" + Instant.now(),
                "source=" + Lab.DEV + " /api/signals/replay（sentinel-v1、止损 2.0×ATR、不减半仓）",
                "replayFrom=" + REPLAY_FROM + " window=[" + WINDOW_FROM + "," + WINDOW_TO + "]",
                "symbols=" + symbols.size() + " h1Symbols=" + (symbols.size() + 2) / 3 + " failures=" + failures.size(),
                "signals=" + rows.size() + " h1Signals=" + h1,
                "byYear=" + byYear,
                "byReason=" + byReason,
                "signalCloseMismatch=" + closeMismatch,
                "sha256(" + OUT + ".csv)=" + Lab.sha256(csv),
                "failures:",
                String.join("\n", failures), "");
        Files.writeString(Lab.DATA.resolve(OUT + "-freeze.txt"), meta, StandardCharsets.UTF_8);
        System.out.println(meta);
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }
}
