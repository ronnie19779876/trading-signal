package org.jdkxx.trader.storage.status;

/**
 * 系统页展示用的数据库状态。不包含主机、端口、密码。
 */
public record DatabaseStatus(boolean enabled, String database, String serverVersion, String marker, String detail) {

    public static DatabaseStatus disabled() {
        return new DatabaseStatus(false, null, null, null, "未启用存储（trader.storage.enabled=false）");
    }
}
