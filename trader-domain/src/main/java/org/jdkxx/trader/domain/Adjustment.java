package org.jdkxx.trader.domain;

/** K 线复权口径。存储一律不复权，读取时按口径计算。 */
public enum Adjustment {
    NONE, FORWARD, BACKWARD
}
