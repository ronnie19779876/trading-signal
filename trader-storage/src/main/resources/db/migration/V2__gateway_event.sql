-- 网关连接事件时间线：盈透每日重启、隧道抖动、心跳失败都会在这里留下痕迹，事后可查"什么时候断的、断了多久"。
CREATE TABLE gateway_event (
    id           bigserial    PRIMARY KEY,
    broker       varchar(8)   NOT NULL CHECK (broker IN ('IBKR', 'FUTU')),
    event        varchar(16)  NOT NULL CHECK (event IN ('CONNECTED', 'RECONNECTED', 'DISCONNECTED', 'ERROR')),
    detail       text,
    occurred_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX gateway_event_occurred_at_idx ON gateway_event (occurred_at DESC);

COMMENT ON TABLE gateway_event IS '券商网关连接事件（连上、重连、断开、不可重试错误）';
