package org.jdkxx.trader.gateway.ibkr.mapper;

import com.ib.client.Contract;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;

/**
 * 领域标的 → TWS API 合约。这是 com.ib.client 类型进入本模块的唯一入口之一。
 *
 * <p>美股用 SMART 路由，primaryExch 用来消除同名歧义（错误 200）。IB Gateway 上 "NASDAQ" 是否可用
 * 待第 1 期对真实网关实测，不行就用 "ISLAND"。
 */
public final class IbkrContracts {

    public static final String DEFAULT_US_PRIMARY_EXCHANGE = "NASDAQ";

    private IbkrContracts() {
    }

    public static Contract stock(Instrument instrument, String primaryExchange) {
        if (instrument.market() != Market.US) {
            throw new IllegalArgumentException("盈透侧目前只映射美股，收到：" + instrument);
        }
        Contract contract = new Contract();
        // 类别股盈透写空格（BRK B），我们与富途写点（BRK.B）；不换的话查无此标的（2026-10-10 实测）
        contract.symbol(instrument.symbol().replace('.', ' '));
        contract.secType("STK");
        contract.exchange("SMART");
        contract.currency("USD");
        if (primaryExchange != null && !primaryExchange.isBlank()) {
            contract.primaryExch(primaryExchange);
        }
        return contract;
    }

    /** 按 conId 定位（历史数据用）：conId 唯一确定合约，交易所给 SMART 即可。 */
    public static Contract byConId(long conId) {
        Contract contract = new Contract();
        contract.conid(Math.toIntExact(conId));
        contract.exchange("SMART");
        return contract;
    }

    /**
     * 取历史数据用的合约：conId + 主交易所。主交易所查不到时退回 SMART。
     * 不能一律用 SMART——SMART 的历史只从最近一次转板开始（见 {@code IbkrGateway#splitAdjustedDailyBars}）。
     */
    public static Contract historical(long conId, String primaryExchange) {
        Contract contract = byConId(conId);
        if (primaryExchange != null && !primaryExchange.isBlank()) {
            contract.exchange(primaryExchange);
        }
        return contract;
    }

    public static Contract usStock(String symbol) {
        return stock(Instrument.us(symbol), DEFAULT_US_PRIMARY_EXCHANGE);
    }
}
