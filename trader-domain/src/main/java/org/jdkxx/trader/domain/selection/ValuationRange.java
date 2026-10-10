package org.jdkxx.trader.domain.selection;

/** 估值历史的回看区间。YEAR10 实测从 2016-10 起逐日约 2513 天；MONTH3 用于公告后覆盖近期。 */
public enum ValuationRange {
    MONTH3,
    YEAR10
}
