package org.jdkxx.trader.domain.selection;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 一季的基本面输入（单季口径，6.1 实测季度现金流 4 季之和 = 年报）。数值缺失为 null。
 *
 * @param periodEnd   真实期末（库内期末 +1：富途日期整体早一天）
 * @param fiscalQ4    期别是不是财年第四季（法定期限 60 天，其余 40 天）
 * @param template    字段模板，决定哪些指标可算
 * @param availableOn 可用日（公告交易日或兜底规则，见 {@link QuarterAvailability}）
 */
public record QuarterFacts(LocalDate periodEnd, boolean fiscalQ4, Template template, LocalDate availableOn,
                           Double revenue, Double grossProfit, Double netIncome, Double epsDiluted,
                           Double equity, Double freeCashFlow) {

    /** 富途财报的三套字段模板（2026-10-12 开发库实测：标准 8xxx、银行 9xxx、保险 10xxx）。 */
    public enum Template {
        STANDARD,
        BANK,
        INSURANCE;

        public boolean financial() {
            return this != STANDARD;
        }
    }

    public QuarterFacts {
        Objects.requireNonNull(periodEnd, "periodEnd");
        Objects.requireNonNull(template, "template");
    }

    public QuarterFacts withAvailableOn(LocalDate d) {
        return new QuarterFacts(periodEnd, fiscalQ4, template, d, revenue, grossProfit, netIncome, epsDiluted, equity, freeCashFlow);
    }
}
