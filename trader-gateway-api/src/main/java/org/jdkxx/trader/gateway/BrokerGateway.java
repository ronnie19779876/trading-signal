package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.Broker;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 券商网关的公共端口：身份、生命周期、状态、账户。行情、参考数据、交易等端口按分期设计逐步加入。
 */
public interface BrokerGateway {

    Broker broker();

    /** 配置里是否启用。未启用时 connect() 立即完成且状态保持 DISABLED。 */
    boolean enabled();

    /** 启用后是否随应用启动自动连接。 */
    boolean autoConnect();

    GatewayStatus status();

    /** 幂等。返回的 Future 在下一次成功就绪时完成；连接失败不抛异常而是进入重连，disconnect() 会让它异常完成。 */
    CompletableFuture<Void> connect();

    /** 幂等、同步：断开并停止重连。 */
    void disconnect();

    /** 盈透：受管账户；富途：交易业务账户。未连接时异常完成（NotConnectedException）。 */
    CompletableFuture<List<AccountRef>> accounts();

    void addListener(GatewayListener listener);
}
