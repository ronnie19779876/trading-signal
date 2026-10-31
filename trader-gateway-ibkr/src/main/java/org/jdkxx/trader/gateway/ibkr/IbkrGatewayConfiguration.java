package org.jdkxx.trader.gateway.ibkr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the IBKR adapter: binds {@link IbkrProperties} and creates the single {@link
 * IbkrGateway} bean.
 */
@Configuration
@EnableConfigurationProperties(IbkrProperties.class)
public class IbkrGatewayConfiguration {

  private static final Logger log = LoggerFactory.getLogger(IbkrGatewayConfiguration.class);

  /**
   * The account-updates feed is enabled only on the production instance (3.4.6): IBKR allows only
   * one API client to subscribe to a given account at a time, so a dev instance subscribing would
   * kick production off. The decision is keyed on the environment declaration rather than a
   * dedicated switch: a switch tends to get copied into dev configs, whereas a wrong environment
   * declaration is caught by the environment guard at startup.
   */
  @Bean(destroyMethod = "close")
  public IbkrGateway ibkrGateway(
      IbkrProperties properties, @Value("${trader.environment:}") String environment) {
    boolean prod = "PROD".equalsIgnoreCase(environment == null ? "" : environment.trim());
    if (properties.enabled()) {
      log.info("盈透账户更新推送（持仓均价与浮盈）：{}", prod ? "启用（生产实例）" : "不启用（非生产实例，均价与浮盈用持仓接口 / 逐只盈亏的原值）");
    }
    return new IbkrGateway(properties, prod);
  }
}
