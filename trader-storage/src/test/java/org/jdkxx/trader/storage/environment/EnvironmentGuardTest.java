package org.jdkxx.trader.storage.environment;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 守卫的顺序：先校验、再迁移。2.0.2 前先迁移后校验，开发实例误连生产库时，
 * SNAPSHOT 里还没发布的迁移会先在生产库上执行，守卫才拒绝启动。
 */
class EnvironmentGuardTest {

    private final List<String> calls = new ArrayList<>();
    private final Runnable migration = () -> calls.add("migrate");

    @Test
    void 标记不一致时一条迁移都不执行() {
        EnvironmentCheckTest.FakeStore prod = new EnvironmentCheckTest.FakeStore("PROD", true, calls);

        assertThatThrownBy(() -> new EnvironmentGuard("DEV").guard(prod, migration))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("环境不匹配");
        assertThat(calls).as("开发实例误连生产库，新迁移不能先落到生产库").isEmpty();
    }

    @Test
    void 有表结构却没有标记时一条迁移都不执行() {
        EnvironmentCheckTest.FakeStore store = new EnvironmentCheckTest.FakeStore(null, true, calls);

        assertThatThrownBy(() -> new EnvironmentGuard("DEV").guard(store, migration))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calls).isEmpty();
    }

    @Test
    void 全新空库先迁移再盖章() {
        EnvironmentCheckTest.FakeStore store = new EnvironmentCheckTest.FakeStore(null, false, calls);

        new EnvironmentGuard("DEV").guard(store, migration);

        assertThat(calls).containsExactly("migrate", "stamp");
        assertThat(store.marker).isEqualTo("DEV");
    }

    @Test
    void 标记一致时照常迁移_不重复盖章() {
        EnvironmentCheckTest.FakeStore store = new EnvironmentCheckTest.FakeStore("DEV", true, calls);

        new EnvironmentGuard("DEV").guard(store, migration);

        assertThat(calls).containsExactly("migrate");
    }
}
