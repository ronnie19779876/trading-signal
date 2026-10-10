package org.jdkxx.trader.domain.selection;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 一期财报的公告日（富途 {@code getFinancialsEarningsPriceHistory}）。
 * <p>实测只给最近 20 季，而且<b>含未来的预定日期</b>（如 AAPL 2026/Q4 预定 11-02）：
 * 公告日晚于判定日的不算已公告。期别文本与财报的 {@code period_text} 同格式（{@code 2026/Q3}），按它匹配。
 *
 * @param pubTimeText 券商给的时刻原文（时区未经证实，只留档不参与计算）
 * @param current     券商标的「当前期」
 */
public record EarningsEvent(String periodText, int fiscalYear, LocalDate pubDate, String pubTimeText,
                            EarningsTiming timing, boolean current) {

    public EarningsEvent {
        Objects.requireNonNull(periodText, "periodText");
        Objects.requireNonNull(pubDate, "pubDate");
        Objects.requireNonNull(timing, "timing");
    }
}
