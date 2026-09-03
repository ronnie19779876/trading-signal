package org.jdkxx.trader.gateway.futu;

import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayStatus;

/**
 * 富途网关适配器。第 0 期只承担配置校验与状态报告；行情连接 / 交易连接、seq→Future 关联、
 * 断线后恢复订阅、额度与限频闸门是第 1 期的内容。
 */
public class FutuGateway implements BrokerGateway {

    private final FutuProperties properties;

    public FutuGateway(FutuProperties properties) {
        properties.validate();
        this.properties = properties;
    }

    @Override
    public Broker broker() {
        return Broker.FUTU;
    }

    @Override
    public GatewayStatus status() {
        if (!properties.enabled()) {
            return GatewayStatus.disabled("未启用（trader.futu.enabled=false）");
        }
        return GatewayStatus.disconnected("已配置，连接层待第 1 期实现");
    }
}
