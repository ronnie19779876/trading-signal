package org.jdkxx.trader.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 券商账户的引用。accountId 是券商侧的账户号（盈透 "U…"/"DU…"，富途 accID 数字），
 * 属于敏感信息：只在服务端内存里流转，对外一律脱敏。
 */
public record AccountRef(Broker broker, String accountId, AccountKind kind, Set<Market> markets) {

    public AccountRef {
        Objects.requireNonNull(broker, "broker");
        Objects.requireNonNull(kind, "kind");
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId 不能为空");
        }
        markets = markets == null || markets.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(markets));
    }

    /** 防止账户号被无意打进日志。 */
    @Override
    public String toString() {
        return "AccountRef[" + broker + " " + kind + " ****" + " " + markets + "]";
    }
}
