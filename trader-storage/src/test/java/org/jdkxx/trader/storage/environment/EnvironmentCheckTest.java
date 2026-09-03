package org.jdkxx.trader.storage.environment;

import org.jdkxx.trader.common.env.AppEnvironment;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentCheckTest {

    private static final class FakeStore implements EnvironmentCheck.MarkerStore {
        String marker;
        String stampedNote;

        FakeStore(String marker) {
            this.marker = marker;
        }

        @Override
        public Optional<String> marker() {
            return Optional.ofNullable(marker);
        }

        @Override
        public String currentDatabase() {
            return "db_trader_dev";
        }

        @Override
        public void stamp(AppEnvironment environment, String note) {
            marker = environment.name();
            stampedNote = note;
        }
    }

    @Test
    void 全新空库自动盖章() {
        FakeStore store = new FakeStore(null);

        EnvironmentCheck.verify(store, AppEnvironment.DEV, true);

        assertThat(store.marker).isEqualTo("DEV");
        assertThat(store.stampedNote).isNotBlank();
    }

    @Test
    void 已有表结构但没有标记的库拒绝认领() {
        FakeStore store = new FakeStore(null);

        assertThatThrownBy(() -> EnvironmentCheck.verify(store, AppEnvironment.DEV, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝自动盖章");
        assertThat(store.marker).isNull();
    }

    @Test
    void 标记一致时通过() {
        EnvironmentCheck.verify(new FakeStore("PROD"), AppEnvironment.PROD, false);
    }

    @Test
    void 标记不一致时拒绝启动并给出修复提示() {
        assertThatThrownBy(() -> EnvironmentCheck.verify(new FakeStore("PROD"), AppEnvironment.DEV, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("环境不匹配")
                .hasMessageContaining("db_trader_dev");
    }
}
