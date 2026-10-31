package org.jdkxx.trader.core.marketdata.universe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jdkxx.trader.domain.IndexCode;
import org.junit.jupiter.api.Test;

class WikipediaUniverseSourceTest {

  private static String fixture(String name) throws IOException {
    try (var in = WikipediaUniverseSourceTest.class.getResourceAsStream("/universe/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * The sample has only 3 rows while the parser needs ≥ 50 rows to trust a table; the sample row is
   * repeated up to 60 rows here.
   */
  private static String inflate(String html, String rowMarker) {
    int i = html.indexOf(rowMarker);
    int end = html.indexOf("</tr>", i) + 5;
    String row = html.substring(i, end);
    String extra =
        IntStream.range(0, 60)
            .mapToObj(n -> row.replace("AAPL", "T" + n).replace("ADBE", "T" + n))
            .collect(Collectors.joining());
    return html.substring(0, end) + extra + html.substring(end);
  }

  @Test
  void 标普表按表头取列并清理脚注与实体() throws Exception {
    String html = inflate(fixture("sp500-sample.html"), "<tr><td><a href=\"#\">AAPL</a>");
    List<ConstituentEntry> entries = WikipediaUniverseSource.parse(IndexCode.SP500, html);

    assertThat(entries).hasSizeGreaterThanOrEqualTo(63);
    ConstituentEntry aapl =
        entries.stream().filter(e -> e.symbol().equals("AAPL")).findFirst().orElseThrow();
    assertThat(aapl.name()).isEqualTo("Apple Inc.");
    assertThat(aapl.sector()).isEqualTo("Information Technology");
    assertThat(aapl.subIndustry()).isEqualTo("Technology Hardware, Storage & Peripherals");
    assertThat(aapl.addedDate()).isEqualTo(LocalDate.of(1982, 11, 30));
    assertThat(entries.get(1).symbol()).isEqualTo("BRK.B");
    // does not accidentally pick the second table (the change history)
    assertThat(entries).noneMatch(e -> e.symbol().equals("XYZ"));
  }

  @Test
  void 纳指表用Ticker与ICB列() throws Exception {
    String html = inflate(fixture("ndx100-sample.html"), "<tr><td>ADBE</td>");
    List<ConstituentEntry> entries = WikipediaUniverseSource.parse(IndexCode.NDX100, html);

    ConstituentEntry adbe =
        entries.stream().filter(e -> e.symbol().equals("ADBE")).findFirst().orElseThrow();
    assertThat(adbe.name()).isEqualTo("Adobe Inc.");
    assertThat(adbe.sector()).isEqualTo("Technology");
    assertThat(adbe.subIndustry()).isEqualTo("Software");
    assertThat(adbe.addedDate()).isNull();
  }

  @Test
  void 行数太少视为页面结构变化() throws Exception {
    assertThatThrownBy(
            () -> WikipediaUniverseSource.parse(IndexCode.SP500, fixture("sp500-sample.html")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不可信");
    assertThatThrownBy(
            () ->
                WikipediaUniverseSource.parse(
                    IndexCode.SP500, "<html><table><tr><td>x</td></tr></table></html>"))
        .isInstanceOf(IllegalStateException.class);
  }
}
