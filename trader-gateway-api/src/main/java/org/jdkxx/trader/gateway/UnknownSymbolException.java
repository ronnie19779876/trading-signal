package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.Broker;

import java.util.Set;

/**
 * 券商拒绝整批请求，理由是批里有它不认识的标的代码。
 *
 * <p>{@link #named()} 是<b>券商自己点名</b>的代码，只是线索，<b>不足以当剔除依据</b>：
 * 2026-10-07 实测 {@code snapshots([PSKY, NOSUCHXYZ, AAPL])}，富途的 retMsg 里只有
 * {@code 未知股票 PSKY}，另一只坏代码根本没被提及。所以调用方必须另行逐只核实
 * （{@code UnknownSymbolGuard} 用 {@code staticInfo} 的 brokerId==0 判定）。
 *
 * <p>同理，拿不出 named（券商换了措辞、或压根没点名）<b>不影响恢复</b>——核实不依赖这段文本。
 */
public class UnknownSymbolException extends RequestRejectedException {

    private final Set<String> named;

    public UnknownSymbolException(Broker broker, int code, String message, Set<String> named) {
        super(broker, code, message);
        this.named = named == null ? Set.of() : Set.copyOf(named);
    }

    /** 券商点名的代码；可能为空，也可能少报（见类注释）。 */
    public Set<String> named() {
        return named;
    }
}
