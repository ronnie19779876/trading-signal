package org.jdkxx.trader.gateway.futu;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FutuProperties.class)
public class FutuGatewayConfiguration {

    @Bean(destroyMethod = "close")
    public FutuGateway futuGateway(FutuProperties properties) {
        return new FutuGateway(properties);
    }
}
