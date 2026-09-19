package org.jdkxx.trader.core.marketdata.fundamentals;

import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.storage.marketdata.CompanyProfileRepository;
import org.jdkxx.trader.storage.marketdata.FinancialRepository;
import org.jdkxx.trader.storage.marketdata.ValuationRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FundamentalsQueryServiceTest {

    private final ValuationRepository valuations = mock(ValuationRepository.class);
    private final FundamentalsQueryService service = new FundamentalsQueryService(mock(InstrumentDirectory.class), valuations,
            mock(FinancialRepository.class), mock(CompanyProfileRepository.class), mock(UniverseScope.class),
            mock(MarketDataProperties.class));

    @Test
    void 缺省取最新有估值的一天() {
        LocalDate latest = LocalDate.of(2026, 9, 18);
        when(valuations.maxTradeDate()).thenReturn(Optional.of(latest));
        when(valuations.findOn(latest)).thenReturn(List.of());

        FundamentalsQueryService.DayValuations v = service.valuationsOn(null);

        assertThat(v.date()).isEqualTo(latest);
        verify(valuations).findOn(latest);
    }

    @Test
    void 指定日期不查最新日期() {
        LocalDate d = LocalDate.of(2026, 9, 10);
        when(valuations.findOn(d)).thenReturn(List.of());

        assertThat(service.valuationsOn(d).date()).isEqualTo(d);
        verify(valuations, never()).maxTradeDate();
    }

    @Test
    void 库里还没有估值时返回空表() {
        when(valuations.maxTradeDate()).thenReturn(Optional.empty());

        FundamentalsQueryService.DayValuations v = service.valuationsOn(null);

        assertThat(v.date()).isNull();
        assertThat(v.rows()).isEmpty();
        verify(valuations, never()).findOn(any());
    }
}
