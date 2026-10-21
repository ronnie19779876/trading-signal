package org.jdkxx.trader.gateway.ibkr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IbkrProperties.class)
public class IbkrGatewayConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IbkrGatewayConfiguration.class);

    /**
     * 账户更新推送只给生产实例（3.4.6）：同一账户同一时间只能有一个 API 客户端订阅，开发实例订了会把生产挤掉。
     * 判据用环境声明而不是单独开关——开关会被抄进开发配置，环境声明错了环境守卫会拦住启动。
     */
    @Bean(destroyMethod = "close")
    public IbkrGateway ibkrGateway(IbkrProperties properties, @Value("${trader.environment:}") String environment) {
        boolean prod = "PROD".equalsIgnoreCase(environment == null ? "" : environment.trim());
        if (properties.enabled()) {
            log.info("盈透账户更新推送（持仓均价与浮盈）：{}", prod ? "启用（生产实例）" : "不启用（非生产实例，均价与浮盈用持仓接口 / 逐只盈亏的原值）");
        }
        return new IbkrGateway(properties, prod);
    }
}
