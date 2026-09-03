package org.jdkxx.trader.gateway.ibkr;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IbkrProperties.class)
public class IbkrGatewayConfiguration {

    @Bean(destroyMethod = "close")
    public IbkrGateway ibkrGateway(IbkrProperties properties) {
        return new IbkrGateway(properties);
    }
}
