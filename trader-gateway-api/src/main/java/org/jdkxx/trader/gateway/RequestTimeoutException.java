package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.Broker;

public class RequestTimeoutException extends GatewayException {

    public RequestTimeoutException(Broker broker, String what) {
        super(broker, 0, what + " 超时", true);
    }
}
