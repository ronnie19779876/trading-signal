package org.jdkxx.trader.storage.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Order of the guard: verify first, then migrate. Before 2.0.2 it migrated first and verified
 * afterwards, so when a dev instance was mistakenly pointed at the production database, the
 * SNAPSHOT's unreleased migrations ran on production before the guard refused to start.
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

  @Test
  void 只读热备上一条迁移都不执行() {
    EnvironmentCheckTest.FakeStore standby =
        new EnvironmentCheckTest.FakeStore("PROD", true, calls);
    standby.recovery = true;

    assertThatThrownBy(() -> new EnvironmentGuard("PROD").guard(standby, migration))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("只读热备");
    assertThat(calls).isEmpty();
  }
}
