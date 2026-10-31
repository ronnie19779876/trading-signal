package org.jdkxx.trader.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the model layer. Both beans are always created, even without an API key: the
 * client is built lazily on first use, and a missing key only fails the actual model call.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfiguration {

  @Bean
  public OpenAiClientFactory openAiClientFactory(AiProperties properties) {
    return new OpenAiClientFactory(properties);
  }

  @Bean
  public org.jdkxx.trader.ai.veto.VetoClient vetoClient(
      OpenAiClientFactory factory, AiProperties properties) {
    return new org.jdkxx.trader.ai.veto.OpenAiVetoClient(factory, properties);
  }
}
