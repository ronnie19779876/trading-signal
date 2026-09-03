package org.jdkxx.trader.common.env;

import java.util.Locale;

/**
 * 运行环境声明。由外置配置 {@code trader.environment} 提供，<b>必填、无默认值</b>：
 * 给默认值意味着漏配时会静默走进某一边，而两个方向都危险（默认 PROD 会让开发实例把新库盖成 PROD，
 * 默认 DEV 会让生产漏配时拒绝启动）。
 */
public enum AppEnvironment {
    DEV, PROD;

    /**
     * 解析配置值。空值或非法值都抛出带修复提示的异常，让应用在启动早期就失败。
     */
    public static AppEnvironment parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("""
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
