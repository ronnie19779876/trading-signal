package org.jdkxx.trader.storage.environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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

  /**
   * Found in the 2026-10-31 review: the marker and standby checks only run inside Flyway's
   * migration strategy, so storage enabled with Flyway excluded (or spring.flyway.enabled=false)
   * started with no environment check at all. The guard now refuses startup when it never ran.
   */
  @Test
  void Flyway没运行时拒绝启动() {
    assertThatThrownBy(() -> new EnvironmentGuard("DEV").afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Flyway 未运行");
  }

  @Test
  void 守卫跑过之后正常启动() {
    EnvironmentGuard guard = new EnvironmentGuard("DEV");
    guard.guard(new EnvironmentCheckTest.FakeStore("DEV", true, calls), migration);

    assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
  }

  /** Wired by the container, not only called by hand: a context without Flyway fails to start. */
  @Test
  void 容器里没有Flyway时启动失败() {
    new ApplicationContextRunner()
        .withPropertyValues("trader.storage.enabled=true", "trader.environment=DEV")
        .withUserConfiguration(EnvironmentGuard.class)
        .run(
            ctx ->
                assertThat(ctx)
                    .hasFailed()
                    .getFailure()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Flyway 未运行"));
  }

  /**
   * Stand-in for Spring Boot's FlywayMigrationInitializer (an InitializingBean that calls the
   * strategy while singletons are being created): the guard's check comes afterwards and passes.
   */
  static class FakeMigrationInitializer implements InitializingBean {
    private final EnvironmentGuard guard;

    FakeMigrationInitializer(EnvironmentGuard guard) {
      this.guard = guard;
    }

    @Override
    public void afterPropertiesSet() {
      guard.guard(new EnvironmentCheckTest.FakeStore("DEV", true), () -> {});
    }
  }

  @Test
  void 容器里Flyway先跑过时正常启动() {
    new ApplicationContextRunner()
        .withPropertyValues("trader.storage.enabled=true", "trader.environment=DEV")
        .withUserConfiguration(EnvironmentGuard.class, FakeMigrationInitializer.class)
        .run(ctx -> assertThat(ctx).hasNotFailed());
  }
}
