package org.jdkxx.trader.domain.selection.sim;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;

/**
 * 模拟账户的现金与权益（纯函数）。全部由各笔成交推出来，不存余额：任何一笔被重算，余额自然跟着对。
 *
 * <p>同一天内的先后：开盘先平（手动 / 止盈请求），再按请求时间先后入场；收盘再按止损平。
 * 所以开盘入场能用的现金含当天开盘平掉的那几笔，不含当天收盘才止损的。
 */
public final class SimLedger {

    private SimLedger() {
    }

    /**
     * 一笔已入场的成交（评分日口径的价格与股数：两者同乘同除，金额不受持有期拆股影响）。
     *
     * @param exitAtOpen 平仓在开盘（手动 / 止盈请求）；false 为收盘（止损）
     */
    public record Fill(long id, Instant requestedAt, LocalDate entryDate, double entry, int shares,
                       LocalDate exitDate, Double exit, boolean exitAtOpen) {

        double cost() {
            return shares * entry;
        }

        double proceeds() {
            return exit == null ? 0 : shares * exit;
        }

        double pnl() {
            return exit == null ? 0 : shares * (exit - entry);
        }

        /** 在 day 开盘之前已经平掉（之前任何一天，或当天开盘） */
        boolean exitedBeforeOpen(LocalDate day) {
            return exitDate != null && (exitDate.isBefore(day) || (exitDate.equals(day) && exitAtOpen));
        }

        boolean exitedByClose(LocalDate day) {
            return exitDate != null && !exitDate.isAfter(day);
        }

        boolean heldAtClose(LocalDate day) {
            return !entryDate.isAfter(day) && !exitedByClose(day);
        }
    }

    /** day 开盘之前已实现的盈亏。 */
    public static double realizedBeforeOpen(Collection<Fill> fills, LocalDate day) {
        return fills.stream().filter(f -> f.exitedBeforeOpen(day)).mapToDouble(Fill::pnl).sum();
    }

    /**
     * day 开盘时、轮到请求时间为 {@code requestedAt} 的这笔入场时，可用的现金：
     * 先扣掉更早入场的（之前的日子，或同一天开盘请求更早的），再加回开盘前已平掉的。
     */
    public static double cashAtOpen(Collection<Fill> fills, LocalDate day, Instant requestedAt, double initial) {
        double cash = initial;
        for (Fill f : fills) {
            if (f.entryDate().isBefore(day) || (f.entryDate().equals(day) && f.requestedAt().isBefore(requestedAt))) {
                cash -= f.cost();
            }
            if (f.exitedBeforeOpen(day)) {
                cash += f.proceeds();
            }
        }
        return cash;
    }

    /** 等权股数：名义 = min((初始 + 已实现) ÷ slots, 可用现金)，向下取整股。 */
    public static int shares(double capital, int slots, double cash, double entry) {
        double notional = Math.min(capital / slots, cash);
        return notional <= 0 || entry <= 0 ? 0 : (int) Math.floor(notional / entry);
    }

    /** 某天收盘后的现金、持仓市值与持仓笔数。 */
    public record Equity(LocalDate date, double cash, double positions, int open) {
        public double total() {
            return cash + positions;
        }
    }

    /**
     * @param closes 每笔的逐日收盘（评分日口径）；当天没有 K 线（停牌）就用之前最近的一根，一根都没有用入场价
     */
    public static Equity equityAtClose(Collection<Fill> fills, LocalDate day, double initial,
                                       Map<Long, NavigableMap<LocalDate, Double>> closes) {
        double cash = initial;
        double positions = 0;
        int open = 0;
        for (Fill f : fills) {
            if (f.entryDate().isAfter(day)) {
                continue;
            }
            cash -= f.cost();
            if (f.exitedByClose(day)) {
                cash += f.proceeds();
            } else {
                NavigableMap<LocalDate, Double> c = closes.get(f.id());
                Map.Entry<LocalDate, Double> e = c == null ? null : c.floorEntry(day);
                positions += f.shares() * (e == null ? f.entry() : e.getValue());
                open++;
            }
        }
        return new Equity(day, cash, positions, open);
    }

    /** 权益序列的最大回撤（比例，≤ 0）；少于两个点为 0。 */
    public static double maxDrawdown(List<Double> equity) {
        double peak = Double.NEGATIVE_INFINITY;
        double worst = 0;
        for (double v : equity) {
            peak = Math.max(peak, v);
            if (peak > 0) {
                worst = Math.min(worst, v / peak - 1);
            }
        }
        return worst;
    }
}
