package org.jdkxx.trader.core.account;

import org.jdkxx.trader.common.text.Masking;
import org.jdkxx.trader.domain.Broker;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * 账户号在库里的形态：带密钥的 HMAC 作键 + 脱敏形式作展示，明文只在服务端内存。
 *
 * <p>为什么不用纯哈希：盈透账户号只有约 10^8 种取值，SHA-256 几秒就能穷举回去，等于明文。
 */
public final class AccountKeys {

    static final int MIN_SECRET_LENGTH = 16;

    private AccountKeys() {
    }

    /** 密钥缺失或过短时拒绝（先于任何券商请求调用，免得白取数据）。 */
    public static void requireSecret(String secret) {
        if (secret == null || secret.trim().length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("未配置 trader.account.key-secret 或不足 " + MIN_SECRET_LENGTH
                    + " 个字符。它属于敏感配置：本机放 config/secrets.yml，服务器放 trader.env 的 TRADER_ACCOUNT_KEY_SECRET");
        }
    }

    public static String key(String secret, Broker broker, String accountId) {
        requireSecret(secret);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.trim().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal((broker.name() + ":" + accountId.trim()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }

    public static String mask(String accountId) {
        return Masking.mask(accountId, 2);
    }
}
