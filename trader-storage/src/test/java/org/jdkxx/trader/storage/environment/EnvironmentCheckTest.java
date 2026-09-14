package org.jdkxx.trader.storage.environment;

import org.jdkxx.trader.common.env.AppEnvironment;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EnvironmentCheckTest {

    static final class FakeStore implements EnvironmentCheck.MarkerStore {
        String marker;
        final boolean history;
        String stampedNote;
        final List<String> calls;

        FakeStore(String marker, boolean history) {
            this(marker, history, new ArrayList<>());
        }

        FakeStore(String marker, boolean history, List<String> calls) {
            this.marker = marker;
            this.history = history;
            this.calls = calls;
        }

        @Override
        public Optional<String> marker() {
            return Optional.ofNullable(marker);
        }

        @Override
        public boolean hasMigrationHistory() {
            return history;
        }

        @Override
        public String currentDatabase() {
            return "db_trader_dev";
        }

        @Override
        public void stamp(AppEnvironment environment, String note) {
            marker = environment.name();
            stampedNote = note;
            calls.add("stamp");
        }
    }

    @Test
    void 全新空库判为迁移后盖章_判定本身不写库() {
        FakeStore store = new FakeStore(null, false);

        assertThat(EnvironmentCheck.verify(store, AppEnvironment.DEV)).isEqualTo(EnvironmentCheck.Decision.STAMP_AFTER_MIGRATION);
        assertThat(store.marker).as("标记表要等迁移建出来").isNull();

        EnvironmentCheck.stampFresh(store, AppEnvironment.DEV);
        assertThat(store.marker).isEqualTo("DEV");
        assertThat(store.stampedNote).isNotBlank();
    }

    @Test
    void 已有表结构但没有标记的库拒绝认领() {
        FakeStore store = new FakeStore(null, true);

        assertThatThrownBy(() -> EnvironmentCheck.verify(store, AppEnvironment.DEV))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝自动盖章");
        assertThat(store.marker).isNull();
    }

    @Test
    void 标记一致时通过() {
        assertThat(EnvironmentCheck.verify(new FakeStore("PROD", true), AppEnvironment.PROD)).isEqualTo(EnvironmentCheck.Decision.MATCHED);
    }

    @Test
    void 标记不一致时拒绝启动并给出修复提示() {
        assertThatThrownBy(() -> EnvironmentCheck.verify(new FakeStore("PROD", true), AppEnvironment.DEV))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("环境不匹配")
                .hasMessageContaining("db_trader_dev");
    }
}
