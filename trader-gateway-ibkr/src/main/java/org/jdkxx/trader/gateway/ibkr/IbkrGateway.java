package org.jdkxx.trader.gateway.ibkr;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayStatus;

/**
 * 盈透网关适配器。第 0 期只承担配置校验与状态报告；连接管理（EClientSocket / EReader 泵线程、
 * nextValidId 就绪信号、断线重连、请求-回调关联、限频闸门）是第 1 期的内容。
 */
public class IbkrGateway implements BrokerGateway {

    private final IbkrProperties properties;

    public IbkrGateway(IbkrProperties properties) {
        properties.validate();
        this.properties = properties;
    }

    @Override
    public Broker broker() {
        return Broker.IBKR;
    }

    @Override
    public GatewayStatus status() {
        if (!properties.enabled()) {
            return GatewayStatus.disabled("未启用（trader.ibkr.enabled=false）");
        }
        return GatewayStatus.disconnected("已配置，连接层待第 1 期实现");
    }
}
