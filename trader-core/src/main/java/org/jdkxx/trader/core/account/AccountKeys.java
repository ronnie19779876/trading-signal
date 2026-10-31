package org.jdkxx.trader.core.account;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jdkxx.trader.common.text.Masking;
import org.jdkxx.trader.domain.Broker;

/**
 * How account numbers are stored: a keyed HMAC as the key plus a masked form for display; the plain
 * text exists only in server memory.
 *
 * <p>Why not a plain hash: IBKR account numbers have only about 10^8 possible values, so a SHA-256
 * can be brute-forced back in seconds, which is as good as plain text. The key is the first 16
 * bytes (128 bits) of HMAC-SHA256 over {@code BROKER:accountId}, hex-encoded.
 */
public final class AccountKeys {

  static final int MIN_SECRET_LENGTH = 16;

  private AccountKeys() {}

  /**
   * Rejects a missing or too short secret (called before any broker request, so no data is fetched
   * in vain).
   */
  public static void requireSecret(String secret) {
    if (secret == null || secret.trim().length() < MIN_SECRET_LENGTH) {
      throw new IllegalStateException(
          "未配置 trader.account.key-secret 或不足 "
              + MIN_SECRET_LENGTH
              + " 个字符。它属于敏感配置：本机放 config/secrets.yml，服务器放 trader.env 的 TRADER_ACCOUNT_KEY_SECRET");
    }
  }

  public static String key(String secret, Broker broker, String accountId) {
    requireSecret(secret);
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.trim().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] h =
          mac.doFinal((broker.name() + ":" + accountId.trim()).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(h, 0, 16);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 不可用", e);
    }
  }

  public static String mask(String accountId) {
    return Masking.mask(accountId, 2);
  }
}
