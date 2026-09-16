package org.jdkxx.trader.domain.signal;

import java.time.LocalDate;
import java.util.List;

/**
 * 由分形点聚类出的价格区间：支撑区来自分形低点，压力区来自分形高点。
 * 区宽不做归一化，它本身就是"该价位被反复测试的密集程度"。
 *
 * @param touches 成员分形点个数（触及次数）
 * @param members 成员分形点的日期，升序
 */
public record PriceZone(double bottom, double top, int touches, List<LocalDate> members) {

    public PriceZone {
        members = List.copyOf(members);
    }

    public boolean contains(double price, double tolerance) {
        return price >= bottom - tolerance && price <= top + tolerance;
    }
}
