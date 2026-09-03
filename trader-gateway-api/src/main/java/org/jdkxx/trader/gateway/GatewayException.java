package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.Broker;

/**
 * 网关层异常。code 是券商侧的错误码（盈透 error code / 富途 retType 或 errCode），没有则为 0。
 */
public class GatewayException extends RuntimeException {

    private final Broker broker;
    private final int code;
    private final boolean retryable;

    public GatewayException(Broker broker, int code, String message, boolean retryable) {
        this(broker, code, message, retryable, null);
    }

    public GatewayException(Broker broker, int code, String message, boolean retryable, Throwable cause) {
        super("[" + broker + (code != 0 ? " " + code : "") + "] " + message, cause);
        this.broker = broker;
        this.code = code;
        this.retryable = retryable;
    }

    public Broker broker() {
        return broker;
    }

    public int code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
