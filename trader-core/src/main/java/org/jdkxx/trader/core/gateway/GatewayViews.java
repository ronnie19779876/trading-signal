package org.jdkxx.trader.core.gateway;

import org.jdkxx.trader.common.text.Masking;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.gateway.BrokerGateway;
import org.jdkxx.trader.gateway.GatewayStatus;
import org.jdkxx.trader.storage.gateway.GatewayEventRecord;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 对外展示的视图。账户号一律脱敏；没有主机、端口、密钥。
 */
public final class GatewayViews {

    private GatewayViews() {
    }

    public record GatewayView(String broker, String displayName, String role, boolean enabled, boolean autoConnect,
                              String state, boolean healthy, String detail, Instant checkedAt, Instant connectedSince,
                              Instant lastHeartbeatAt, int reconnectAttempts, Map<String, String> facts) {
    }

    public record AccountView(String broker, String maskedId, String kind, Set<String> markets) {
    }

    public record EventView(long id, String broker, String event, String detail, Instant occurredAt) {
    }

    public static GatewayView of(BrokerGateway g) {
        GatewayStatus s = g.status();
        return new GatewayView(g.broker().name(), g.broker().displayName(), g.broker().role(), g.enabled(),
                g.autoConnect(), s.state().name(), s.healthy(), s.detail(), s.checkedAt(), s.connectedSince(),
                s.lastHeartbeatAt(), s.reconnectAttempts(), s.facts());
    }

    public static AccountView of(AccountRef a) {
        return new AccountView(a.broker().name(), Masking.mask(a.accountId(), 2), a.kind().name(),
                a.markets().stream().map(Enum::name).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new)));
    }

    public static EventView of(GatewayEventRecord r) {
        return new EventView(r.id(), r.broker().name(), r.event(), r.detail(), r.occurredAt());
    }
}
