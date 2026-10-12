package org.jdkxx.trader.core.selection;

import org.jdkxx.trader.domain.IndexCode;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.SecurityType;
import org.jdkxx.trader.storage.marketdata.ConstituentRow;
import org.jdkxx.trader.storage.marketdata.IndexConstituentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SelectionSectorsTest {

    private static InstrumentRow row(long id, String symbol) {
        return new InstrumentRow(id, Market.US, symbol, symbol, null, SecurityType.STOCK, 1, null, false, null, null, "RESOLVED");
    }

    @Test
    void GICS优先_纳指独有的查覆盖表_ADR没有板块() {
        IndexConstituentRepository repo = mock(IndexConstituentRepository.class);
        when(repo.currentAll()).thenReturn(List.of(
                new ConstituentRow(IndexCode.SP500, 1, "Information Technology", "Semis", "GICS", LocalDate.of(2020, 1, 1), null, "wiki"),
                new ConstituentRow(IndexCode.NDX100, 1, "Technology", "Semis", "ICB", LocalDate.of(2020, 1, 1), null, "wiki"),
                new ConstituentRow(IndexCode.NDX100, 2, "Health Care", "Biotech", "ICB", LocalDate.of(2020, 1, 1), null, "wiki"),
                new ConstituentRow(IndexCode.NDX100, 3, "Technology", "Semis", "ICB", LocalDate.of(2020, 1, 1), null, "wiki")));
        Map<Long, String> s = new SelectionSectors(repo).of(List.of(row(1, "NVDA"), row(2, "ALNY"), row(3, "TSM")));

        assertThat(s).containsEntry(1L, "Information Technology").containsEntry(2L, "Health Care").doesNotContainKey(3L);
    }

    @Test
    void 覆盖表读得到且每个板块都是GICS名称() {
        Map<String, String> o = SelectionSectors.loadOverrides();
        List<String> gics = List.of("Communication Services", "Consumer Discretionary", "Consumer Staples", "Energy", "Financials",
                "Health Care", "Industrials", "Information Technology", "Materials", "Real Estate", "Utilities");

        assertThat(o).hasSize(12).doesNotContainKeys("ASML", "TSM", "PDD", "ARM");
        assertThat(o.values()).allMatch(gics::contains);
    }
}
