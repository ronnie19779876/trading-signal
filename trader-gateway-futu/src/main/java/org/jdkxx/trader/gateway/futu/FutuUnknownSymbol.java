package org.jdkxx.trader.gateway.futu;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从富途的 retMsg 里认出「未知股票」这类拒绝，并把它点名的代码摘出来。
 * 富途特有的文本解析<b>只在这一处</b>，core 不碰措辞。
 *
 * <p>实测原文（2026-10-06 生产日志、2026-10-07 探针各复现一次）：
 * <pre>
 * getSecuritySnapshot 失败：未知股票 PSKY（retType=-1）
 * sub KL_Day ×90 失败：未知股票 PSKY（retType=-1）
 * </pre>
 *
 * <p><b>这段解析是锦上添花，不是判定依据。</b>认不出来只是少一条交叉核对的线索，
 * 恢复照常走——坏代码由 {@code staticInfo} 的 brokerId==0 结构化判定。所以这里不必追求
 * 覆盖富途所有可能的措辞，也不该为"认不出"去放宽模式（放宽只会多误报几个词当代码）。
 */
final class FutuUnknownSymbol {

    /** 「未知股票 XXX」。代码取美股常见字符集（含 BRK.B 这类带点的）。 */
    private static final Pattern NAMED = Pattern.compile("未知股票\\s*([A-Za-z][A-Za-z0-9.\\-]{0,15})");

    private FutuUnknownSymbol() {
    }

    /** retMsg 像不像「未知股票」这类拒绝。 */
    static boolean looksUnknown(String retMsg) {
        return retMsg != null && retMsg.contains("未知股票");
    }

    /** 从 retMsg 里摘出被点名的代码（可能为空）。 */
    static Set<String> named(String retMsg) {
        Set<String> out = new LinkedHashSet<>();
        if (retMsg == null) {
            return out;
        }
        Matcher m = NAMED.matcher(retMsg);
        while (m.find()) {
            out.add(m.group(1).toUpperCase(java.util.Locale.ROOT));
        }
        return out;
    }
}
