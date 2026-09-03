package org.jdkxx.trader.ai;

/**
 * 系统页展示用：是否配置了 key、用哪个模型。绝不包含 key 本身。
 */
public record AiStatus(boolean configured, String model, String detail) {
}
