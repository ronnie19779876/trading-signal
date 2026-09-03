package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.InstrumentInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 参考数据（合约明细）端口。本期只有盈透实现。找不到返回空列表，而不是异常。
 */
public interface ReferenceDataGateway {

    CompletableFuture<List<InstrumentInfo>> lookup(Instrument instrument);
}
