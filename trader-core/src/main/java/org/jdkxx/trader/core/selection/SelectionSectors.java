package org.jdkxx.trader.core.selection;

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
import org.jdkxx.trader.storage.marketdata.ConstituentRow;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;

/**
 * Sectors for quant selection (§25.3 rule 2): the GICS sector of current S&amp;P 500 constituents;
 * Nasdaq-only non-ADR names are looked up in the in-jar {@code selection/sector-overrides.csv}.
 * Neither available means no sector (recorded as NO_SECTOR when scoring). The current
 * classification is applied to all of history (the 2023 GICS reclassification is not back-dated,
 * §25.8 item 3).
 */
public class SelectionSectors {

  static final String OVERRIDES = "selection/sector-overrides.csv";

  private final IndexConstituentRepository constituents;
  private final Map<String, String> overrides;

  public SelectionSectors(IndexConstituentRepository constituents) {
    this.constituents = constituents;
    this.overrides = loadOverrides();
  }

  /** Instrument id to GICS sector. */
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

  /**
   * Parses the override CSV: '#' lines and blank lines are skipped, the first remaining line is the
   * header.
   */
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
