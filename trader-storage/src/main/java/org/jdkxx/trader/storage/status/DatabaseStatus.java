package org.jdkxx.trader.storage.status;

/** Database status shown on the system page. Deliberately excludes host, port and credentials. */
public record DatabaseStatus(
    boolean enabled, String database, String serverVersion, String marker, String detail) {

  public static DatabaseStatus disabled() {
    return new DatabaseStatus(false, null, null, null, "未启用存储（trader.storage.enabled=false）");
  }
}
