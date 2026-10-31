package org.jdkxx.trader.storage.environment;

import java.util.Optional;
import org.jdkxx.trader.common.env.AppEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decision logic of the environment guard (pure logic so it can be tested; data access is injected
 * through {@link MarkerStore}).
 *
 * <p>Why it exists: the JDBC URLs of the dev and prod databases differ only by a suffix, so a
 * single copied config can point a dev instance at the production database, and <b>a wrong
 * connection looks perfectly normal from the data</b>. So a marker is carved into the database
 * itself and compared at startup; on mismatch the app refuses to start.
 *
 * <p><b>The check must run before migrations</b>: before 2.0.2 the app migrated first and verified
 * afterwards, so a dev instance that was mistakenly pointed at production ran its not-yet-released
 * migrations against production before the guard refused to start. Startup was blocked, but the
 * schema had already been changed.
 *
 * <p><b>A read-only hot standby is always refused</b> (3.4.5, ARCHITECTURE §27): the VPS and
 * Shanghai environments are primary/standby of each other via physical streaming replication. The
 * standby database is byte-identical to the primary and its marker is also PROD, so the marker
 * alone cannot tell them apart. An instance started on the standby fails every write and all jobs
 * die without any environment error; more importantly, the two sides must never run at the same
 * time. To run on this side, first promote it to primary following the switchover procedure.
 *
 * <p>Automatic stamping only happens on a <b>brand-new empty database</b>. The criterion is "the
 * database has no Flyway history table", not "the marker is empty"; otherwise the first instance to
 * connect could claim any database and a database full of data would be silently relabelled. The
 * marker table is created by a migration, so stamping happens after migrating.
 */
public final class EnvironmentCheck {

  private static final Logger log = LoggerFactory.getLogger(EnvironmentCheck.class);

  /** Result of the pre-migration check. Cases that must not proceed throw instead. */
  public enum Decision {
    /** The marker matches the declared environment. */
    MATCHED,
    /** Brand-new empty database: stamp it once the migrations have created the marker table. */
    STAMP_AFTER_MIGRATION
  }

  /** Reads and writes the marker. */
  public interface MarkerStore {

    /** The environment marker; empty while the marker table does not exist yet (empty database). */
    Optional<String> marker();

    /** Whether the database already has a Flyway migration history table. */
    boolean hasMigrationHistory();

    String currentDatabase();

    /** Whether the connection is to a read-only hot standby ({@code pg_is_in_recovery()}). */
    boolean inRecovery();

    void stamp(AppEnvironment environment, String note);
  }

  private EnvironmentCheck() {}

  /**
   * Call <b>before</b> migrating. Throws when the marker mismatches, when the database has a schema
   * but no marker, or when it is a standby; in those cases not a single migration runs.
   *
   * <p>The standby check comes first on purpose: a standby carries the primary's PROD marker, so it
   * would otherwise pass the marker comparison.
   */
  public static Decision verify(MarkerStore store, AppEnvironment declared) {
    if (store.inRecovery()) {
      throw new IllegalStateException(
          ("""
                    连接的库「%s」是只读热备，拒绝启动（未执行任何迁移）。

                      两套环境互为主备、不能同时运行：备库这边的数据是主库的复制品，任何写入都会失败。
                      要在这台机器上运行，先按 docs/OPERATIONS.md「主备切换」停掉另一边的实例，再把本机提升为主库。""")
              .formatted(store.currentDatabase()));
    }
    Optional<String> actual = store.marker();

    if (actual.isEmpty()) {
      if (store.hasMigrationHistory()) {
        throw new IllegalStateException(
            ("""
                        环境标记为空，但库「%s」在本次启动前已经存在表结构，拒绝自动盖章。

                          自动盖章只对**全新的空库**生效。一个已经建过表的库突然被某个实例认领成 %s，
                          多半意味着配置指错了库——这正是本守卫要防的事。

                          若确认无误（例如给既有库补标记），显式执行：
                            INSERT INTO app_environment (id, name, note) VALUES (1, '%s', '手工补标记');""")
                .formatted(store.currentDatabase(), declared, declared));
      }
      return Decision.STAMP_AFTER_MIGRATION;
    }

    if (!actual.get().equals(declared.name())) {
      throw new IllegalStateException(
          ("""
                    环境不匹配，拒绝启动（未执行任何迁移）。

                      本实例声明:   %s   （trader.environment）
                      数据库标记为: %s   （app_environment 表）
                      连接的库:     %s

                      多半是 spring.datasource.url 指错了库：开发实例只能连 db_trader_dev，生产实例只能连 db_trader。
                      若确实是刚从生产 dump 灌进开发库、需要改标记，执行：
                        UPDATE app_environment SET name = 'DEV', stamped_at = now(), note = '从生产 dump 灌入后改标记';
                      这一步刻意不自动做——它应当是个有意识的动作。""")
              .formatted(declared, actual.get(), store.currentDatabase()));
    }

    log.info("环境校验通过：{} 实例连接库「{}」（标记一致）", declared, store.currentDatabase());
    return Decision.MATCHED;
  }

  /** Stamps a brand-new empty database after its migrations have completed. */
  public static void stampFresh(MarkerStore store, AppEnvironment declared) {
    store.stamp(declared, "空库首次启动自动写入");
    log.info("空库首次启动，已给库「{}」盖上环境标记 {}，此库从此只接受 {} 实例", store.currentDatabase(), declared, declared);
  }
}
