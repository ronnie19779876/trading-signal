package org.jdkxx.trader.storage.environment;

import org.jdkxx.trader.common.env.AppEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 环境守卫的判定逻辑（纯逻辑，便于测试；数据访问通过 {@link MarkerStore} 注入）。
 *
 * <p>为什么需要它：开发库与生产库的 JDBC URL 只差一个后缀，复制一次配置就可能把开发实例接到生产库上，
 * 而且<b>接错了数据看起来完全正常</b>。所以在库里刻一个标记，启动时比对，不一致就起不来。
 *
 * <p><b>判定必须在迁移之前</b>：2.0.2 前先迁移后校验，开发实例误连生产库时，还没发布的迁移会先在生产库上执行，
 * 守卫才拒绝启动——拦住了启动，却没拦住表结构被改。
 *
 * <p>自动盖章只发生在<b>全新的空库</b>上：判据是"库里没有 Flyway 迁移记录表"，而不是"标记为空"。
 * 否则第一个连上的实例可以认领任何库，一个装着数据的库会被悄悄改名。标记表由迁移建出，所以盖章在迁移之后。
 */
public final class EnvironmentCheck {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentCheck.class);

    /** 迁移前判定的结果。不能放行的情形直接抛异常。 */
    public enum Decision {
        /** 标记与声明一致 */
        MATCHED,
        /** 全新空库：迁移建出标记表后盖章 */
        STAMP_AFTER_MIGRATION
    }

    /** 标记的读写。 */
    public interface MarkerStore {

        /** 环境标记；标记表还不存在（空库）时为空。 */
        Optional<String> marker();

        /** 库里是否已有 Flyway 迁移记录表。 */
        boolean hasMigrationHistory();

        String currentDatabase();

        void stamp(AppEnvironment environment, String note);
    }

    private EnvironmentCheck() {
    }

    /** 迁移<b>之前</b>调用：标记不一致、或有表结构却没有标记时抛异常，迁移一步都不做。 */
    public static Decision verify(MarkerStore store, AppEnvironment declared) {
        Optional<String> actual = store.marker();

        if (actual.isEmpty()) {
            if (store.hasMigrationHistory()) {
                throw new IllegalStateException(("""
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
            throw new IllegalStateException(("""
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

    /** 全新空库迁移完成后盖章。 */
    public static void stampFresh(MarkerStore store, AppEnvironment declared) {
        store.stamp(declared, "空库首次启动自动写入");
        log.info("空库首次启动，已给库「{}」盖上环境标记 {}，此库从此只接受 {} 实例", store.currentDatabase(), declared, declared);
    }
}
