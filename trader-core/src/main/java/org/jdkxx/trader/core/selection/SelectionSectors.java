package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.storage.marketdata.ConstituentRow;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 量化选股的板块（§25.3 第 2 条）：标普 500 现任成分的 GICS 板块；纳指独有的非 ADR 标的查 jar 内
 * {@code selection/sector-overrides.csv}。都没有 → 没有板块（打分时记 NO_SECTOR）。
 * 用当前分类套用全部历史（2023 年 GICS 改分类不回溯，§25.8 第 3 条）。
 */
public class SelectionSectors {

    static final String OVERRIDES = "selection/sector-overrides.csv";

    private final IndexConstituentRepository constituents;
    private final Map<String, String> overrides;

    public SelectionSectors(IndexConstituentRepository constituents) {
        this.constituents = constituents;
        this.overrides = loadOverrides();
    }

    /** 标的 id → GICS 板块。 */
    public Map<Long, String> of(Collection<InstrumentRow> targets) {
        Map<Long, String> gics = new HashMap<>();
        for (ConstituentRow r : constituents.currentAll()) {
            if ("GICS".equals(r.classification()) && r.sector() != null && !r.sector().isBlank()) {
                gics.put(r.instrumentId(), r.sector());
            }
        }
        Map<Long, String> out = new HashMap<>();
        for (InstrumentRow t : targets) {
            String s = gics.get(t.id());
            if (s == null) {
                s = overrides.get(t.symbol().toUpperCase(Locale.ROOT));
            }
            if (s != null) {
                out.put(t.id(), s);
            }
        }
        return out;
    }

    Map<String, String> overrides() {
        return overrides;
    }

    static Map<String, String> loadOverrides() {
        Map<String, String> out = new HashMap<>();
        try (InputStream in = SelectionSectors.class.getClassLoader().getResourceAsStream(OVERRIDES)) {
            if (in == null) {
                throw new IllegalStateException("缺少 jar 内资源 " + OVERRIDES);
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            boolean header = true;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (header) {
                    header = false;
                    continue;
                }
                String[] f = line.split(",", 3);
                if (f.length >= 2 && !f[0].isBlank() && !f[1].isBlank()) {
                    out.put(f[0].trim().toUpperCase(Locale.ROOT), f[1].trim());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.copyOf(out);
    }
}
