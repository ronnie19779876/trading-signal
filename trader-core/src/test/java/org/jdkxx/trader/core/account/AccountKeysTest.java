package org.jdkxx.trader.core.account;

import org.jdkxx.trader.domain.Broker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 账户号是虚构的，不能长得像真账户号（扫描器会拦）。 */
class AccountKeysTest {

    private static final String SECRET = "test-only-secret-0123456789";   // secrets-ok 测试常量，不是真密钥

    @Test
    void 同一密钥同一账户结果稳定_32位十六进制_不含账户号() {
        String a = AccountKeys.key(SECRET, Broker.IBKR, "ACCT-A");

        assertThat(a).hasSize(32).matches("[0-9a-f]{32}").isEqualTo(AccountKeys.key(SECRET, Broker.IBKR, " ACCT-A "));
        assertThat(a).doesNotContain("ACCT");
    }

    @Test
    void 换密钥或换账户结果不同() {
        String a = AccountKeys.key(SECRET, Broker.IBKR, "ACCT-A");

        assertThat(AccountKeys.key(SECRET + "x", Broker.IBKR, "ACCT-A")).isNotEqualTo(a);
        assertThat(AccountKeys.key(SECRET, Broker.IBKR, "ACCT-B")).isNotEqualTo(a);
    }

    @Test
    void 密钥缺失或过短时拒绝() {
        assertThatThrownBy(() -> AccountKeys.key(null, Broker.IBKR, "ACCT-A"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("key-secret");
        assertThatThrownBy(() -> AccountKeys.requireSecret("short-secret"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("16");
    }

    @Test
    void 脱敏只留前两位() {
        assertThat(AccountKeys.mask("ACCT-A")).isEqualTo("AC*****");
    }
}
