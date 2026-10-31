package org.jdkxx.trader.common.env;

import java.util.Locale;

/**
 * Declared runtime environment. Supplied by the external config property {@code
 * trader.environment}; it is <b>mandatory and has no default</b>. A default would mean a missing
 * setting silently falls into one side, and both directions are dangerous: defaulting to PROD would
 * let a development instance stamp a fresh database as PROD, and defaulting to DEV would make
 * production refuse to start when the setting is forgotten.
 *
 * <p>The parsed value is compared against the {@code app_environment} marker stored in the database
 * so that a development instance can never attach to the production database.
 */
public enum AppEnvironment {
  DEV,
  PROD;

  /**
   * Parses the configured value. A blank or unknown value throws an exception that carries a fix-it
   * hint, so the application fails early during startup instead of running with a guessed
   * environment.
   */
  public static AppEnvironment parse(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(
          """
                    未声明 trader.environment，拒绝启动。
                      本属性没有默认值，必须在外置配置（config/application.yml 或 deploy/config/application.yml）里显式写明：
                        trader:
                          environment: DEV      # 本机开发（只能连 db_trader_dev）
                          environment: PROD     # 生产
                      它用来与数据库里的 app_environment 标记比对，防止开发实例连到生产库。""");
    }
    try {
      return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("trader.environment 只能是 DEV 或 PROD，当前为：" + raw);
    }
  }
}
