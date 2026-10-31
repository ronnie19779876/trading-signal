package org.jdkxx.trader.core.gateway;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.springframework.stereotype.Component;

/**
 * The gateways indexed by broker. Adapters are wired in the app layer; this class only knows the
 * port interface. Registering two implementations for the same broker fails fast at startup.
 */
@Component
public class GatewayRegistry {

  private final Map<Broker, BrokerGateway> gateways = new EnumMap<>(Broker.class);

  public GatewayRegistry(List<BrokerGateway> gateways) {
    for (BrokerGateway g : gateways) {
      if (this.gateways.putIfAbsent(g.broker(), g) != null) {
        throw new IllegalStateException("券商 " + g.broker() + " 注册了多个网关实现");
      }
    }
  }

  public List<BrokerGateway> all() {
    return gateways.values().stream()
        .sorted(Comparator.comparing(g -> g.broker().ordinal()))
        .toList();
  }

  public Optional<BrokerGateway> find(Broker broker) {
    return Optional.ofNullable(gateways.get(broker));
  }

  public BrokerGateway require(Broker broker) {
    return find(broker).orElseThrow(() -> new IllegalArgumentException("没有 " + broker + " 的网关"));
  }
}
