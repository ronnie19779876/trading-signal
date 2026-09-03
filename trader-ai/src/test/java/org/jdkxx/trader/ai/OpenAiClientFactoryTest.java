package org.jdkxx.trader.ai;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiClientFactoryTest {

    @Test
    void 未配置key时状态为未配置且取客户端报错() {
        OpenAiClientFactory factory = new OpenAiClientFactory(new AiProperties("", "m", null, Duration.ofSeconds(1), 0));

        assertThat(factory.status().configured()).isFalse();
        assertThat(factory.status().model()).isEqualTo("m");
        assertThatThrownBy(factory::client).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 配置了key时能构建客户端且状态不泄露key() {
        OpenAiClientFactory factory = new OpenAiClientFactory(new AiProperties("sk-test-not-real", "m", null, Duration.ofSeconds(1), 0));

        assertThat(factory.status().configured()).isTrue();
        assertThat(factory.status().toString()).doesNotContain("sk-test");
        assertThat(factory.client()).isSameAs(factory.client());
    }
}
