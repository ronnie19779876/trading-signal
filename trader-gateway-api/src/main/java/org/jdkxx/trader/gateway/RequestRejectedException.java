package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.Broker;

/** 券商明确拒绝了请求（带错误码），一般不可重试。 */
public class RequestRejectedException extends GatewayException {

    public RequestRejectedException(Broker broker, int code, String message) {
        super(broker, code, message, false);
    }
}
