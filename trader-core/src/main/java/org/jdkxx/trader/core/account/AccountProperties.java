package org.jdkxx.trader.core.account;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.util.List;

/**
 * 账户与持仓配置（{@code trader.account.*}，第 3 期）。
 *
 * <p>keySecret 与 cashEquivalents 属于敏感配置，<b>不写在入库的 yml 里</b>：本机放 {@code config/secrets.yml}，
 * 服务器用环境变量 {@code TRADER_ACCOUNT_KEY_SECRET / TRADER_ACCOUNT_CASH_EQUIVALENTS}。
 *
 * @param enabled           是否装配账户快照的定时任务（手工接口不受影响）
 * @param snapshotCron      每日快照时点（美东，排在增量与估值之后）
 * @param keySecret         账户号 HMAC 的密钥（至少 16 个字符）
 * @param cashEquivalents   用户用来管理现金的持仓代码：计入市值，但不进池、不参与持仓集合核对
 * @param valueTolerance    市值对账容差（比例），超过 5 倍记 FAIL
 * @param identityTolerance 资金恒等式容差（金额）
 */
@ConfigurationProperties(prefix = "trader.account")
public record AccountProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0 0 18 * * MON-FRI") String snapshotCron,
        String keySecret,
        @DefaultValue List<String> cashEquivalents,
        @DefaultValue("0.002") BigDecimal valueTolerance,
        @DefaultValue("1") BigDecimal identityTolerance) {
}
