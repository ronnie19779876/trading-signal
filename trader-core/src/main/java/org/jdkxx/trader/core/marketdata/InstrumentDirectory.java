package org.jdkxx.trader.core.marketdata;

import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.storage.marketdata.InstrumentRepository;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;

import java.util.NoSuchElementException;

/** 标的查找的小门面：按代码找不到就抛 404 语义的异常。 */
public class InstrumentDirectory {

    private final InstrumentRepository instruments;

    public InstrumentDirectory(InstrumentRepository instruments) {
        this.instruments = instruments;
    }

    public InstrumentRow require(String symbol) {
        Instrument i = Instrument.us(symbol);
        return instruments.find(i).orElseThrow(() -> new NoSuchElementException(
                "标的 " + i.symbol() + " 不在库里：先同步成分股（POST /api/universe/sync）或导入 CSV"));
    }

    public InstrumentRow require(long id) {
        return instruments.findById(id).orElseThrow(() -> new NoSuchElementException("标的 id=" + id + " 不存在"));
    }
}
