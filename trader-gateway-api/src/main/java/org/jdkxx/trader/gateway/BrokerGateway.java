package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.Broker;

/**
 * 券商网关的公共端口。第 0 期只有身份与状态；连接管理、行情、账户、交易等端口按分期设计逐步加入
 * （盈透侧：账户资金 / 持仓盈亏 / 下单；富途侧：K 线 / 基本面 / 实时订阅）。
 */
public interface BrokerGateway {

    Broker broker();

    GatewayStatus status();
}
