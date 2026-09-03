package org.jdkxx.trader.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {

    @Bean
    public OpenAiClientFactory openAiClientFactory(AiProperties properties) {
        return new OpenAiClientFactory(properties);
    }
}
