package org.jdkxx.trader.ai;

/**
 * For the system page: whether a key is configured and which model is used. Never contains the key
 * itself.
 */
public record AiStatus(boolean configured, String model, String detail) {}
