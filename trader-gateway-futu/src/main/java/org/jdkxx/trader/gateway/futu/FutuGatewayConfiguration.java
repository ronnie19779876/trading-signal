package org.jdkxx.trader.gateway.futu;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the Futu gateway. The bean is always created; with {@code
 * trader.futu.enabled=false} it is an inert shell that reports DISABLED. close() on shutdown
 * disconnects both channels and stops the executors.
 */
@Configuration
@EnableConfigurationProperties(FutuProperties.class)
public class FutuGatewayConfiguration {

  @Bean(destroyMethod = "close")
  public FutuGateway futuGateway(FutuProperties properties) {
    return new FutuGateway(properties);
  }
}
