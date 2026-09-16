package org.jdkxx.trader.domain.signal;

import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.RehabFactor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 信号判定的价量口径（纯函数）：<b>结构口径</b>，以判定日为基准的前复权，只调结构性事件、不调普通分红。
 *
 * <ul>
 *   <li><b>价格</b>：带拆股、合股、送股、转增、分拆、特别股息任一标记的事件要调。只有股数变动的按精确比例
 *       （富途 fwdA 只保留 5 位小数）；混有分拆或股息的按富途因子 p' = p × fwdA + fwdB；
 *       只有普通分红的事件不调。分拆与特别股息会让原始价跳空（实测 DD 2025-11-03 分拆，收盘 81.65 → 34.69），
 *       只按拆股调整的话，事件后约 200 个交易日的 SMA200、ATR、支撑区全部失真（用户 2026-09-17 决定）；</li>
 *   <li><b>成交量</b>：只按股数变动比例调（拆股、合股 ert/base，送股 (base+ert)/base），分拆与股息不改变股数。
 *       不能用 fwdA 调量：合股与分拆可以同在一个事件里（实测 HON 2026-06-29 flag=258，合股 2:1、fwdA=1.09203）；</li>
 *   <li><b>只用判定日及之前除权的事件</b>：之后的事件属于未来信息，判定日那根恒为原始价，止损价就是当时可成交的价格。</li>
 * </ul>
 * 带股数变动标记却缺比例（V10 之前入库、尚未重拉的因子），或遇到未实测过的转增事件，整体返回问题，不猜。
 * 空 K（blank）剔除。
 */
public final class StructuralAdjustment {

    private static final long STRUCTURAL = RehabFactor.ACT_SPLIT | RehabFactor.ACT_JOIN | RehabFactor.ACT_BONUS
            | RehabFactor.ACT_TRANSFER | RehabFactor.ACT_SPIN_OFF | RehabFactor.ACT_SP_DIVIDEND;

    private StructuralAdjustment() {
    }

    /** bars 非空或 problem 非空，二者有且仅有一个有意义。 */
    public record Result(List<SignalBar> bars, String problem) {
        public boolean ok() {
            return problem == null;
        }
    }

    public static Result apply(List<DailyBar> raw, List<RehabFactor> factors, LocalDate asOf) {
        LocalDate earliest = raw.stream().filter(b -> !b.blank()).map(DailyBar::tradeDate).min(LocalDate::compareTo).orElse(asOf);
        List<Event> events = new ArrayList<>();
        for (RehabFactor f : factors) {
            // 只看会作用到这批 K 线的事件：除权日晚于最早那根、不晚于判定日。更早的事件不影响结果，缺比例也不该拦下判定
            if (!f.exDate().isAfter(earliest) || f.exDate().isAfter(asOf) || (f.companyActFlag() & STRUCTURAL) == 0) {
                continue;
            }
            if (f.has(RehabFactor.ACT_TRANSFER)) {
                return new Result(List.of(), f.exDate() + " 转增事件的口径未实测（flag=" + f.companyActFlag() + "）");
            }
            double shares = 1;
            if (f.has(RehabFactor.ACT_SPLIT)) {
                if (f.splitBase() <= 0 || f.splitErt() <= 0) {
                    return missing(f);
                }
                shares *= (double) f.splitErt() / f.splitBase();
            }
            if (f.has(RehabFactor.ACT_JOIN)) {
                if (f.joinBase() <= 0 || f.joinErt() <= 0) {
                    return missing(f);
                }
                shares *= (double) f.joinErt() / f.joinBase();
            }
            if (f.has(RehabFactor.ACT_BONUS)) {
                if (f.bonusBase() <= 0 || f.bonusErt() <= 0) {
                    return missing(f);
                }
                shares *= (double) (f.bonusBase() + f.bonusErt()) / f.bonusBase();
            }
            long cashLike = RehabFactor.ACT_SPIN_OFF | RehabFactor.ACT_SP_DIVIDEND | RehabFactor.ACT_DIVIDEND;
            boolean pureShareChange = (f.companyActFlag() & cashLike) == 0 && f.fwdB().signum() == 0;
            // 纯股数变动用精确比例：富途 fwdA 只保留 5 位小数（WMT 拆股 1:3 给 0.33333），事件前后会错开 1e-5，
            // 足以让边界上的严格比较翻转
            double a = pureShareChange ? 1 / shares : f.fwdA().doubleValue();
            events.add(new Event(f.exDate(), a, pureShareChange ? 0 : f.fwdB().doubleValue(), shares));
        }
        events.sort(Comparator.comparing(Event::exDate));

        List<SignalBar> out = new ArrayList<>(raw.size());
        for (DailyBar b : raw) {
            if (b.blank() || b.tradeDate().isAfter(asOf)) {
                continue;
            }
            double a = 1;
            double bb = 0;
            double shares = 1;
            // 先应用较早的事件，再应用较晚的：p' = A2 (A1 p + B1) + B2（富途因子逐事件复合，见 FactorMode）
            for (Event e : events) {
                if (e.exDate().isAfter(b.tradeDate())) {
                    bb = e.a() * bb + e.b();
                    a = e.a() * a;
                    shares *= e.shares();
                }
            }
            out.add(new SignalBar(b.tradeDate(),
                    b.open().doubleValue() * a + bb,
                    b.high().doubleValue() * a + bb,
                    b.low().doubleValue() * a + bb,
                    b.close().doubleValue() * a + bb,
                    b.volume() * shares));
        }
        return new Result(out, null);
    }

    private static Result missing(RehabFactor f) {
        return new Result(List.of(), f.exDate() + " 的股数变动事件缺比例（flag=" + f.companyActFlag() + "），等复权因子重拉");
    }

    private record Event(LocalDate exDate, double a, double b, double shares) {
    }
}
