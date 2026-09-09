package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.QotCommon;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.MarketSession;
import org.jdkxx.trader.domain.Quote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 富途 BasicQot → 领域报价。
 * 实测（盘前）：curPrice / volume / updateTime 冻结在上个收盘，只有 preMarket 子结构更新；有效价按时段取。
 */
public final class FutuQuotes {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]");

    private FutuQuotes() {
    }

    public static Quote toQuote(QotCommon.BasicQot q, MarketSession session, Instant receivedAt) {
        Instrument instrument = new Instrument(FutuSecurities.market(q.getSecurity().getMarket()), q.getSecurity().getCode());
        BigDecimal cur = price(q.getCurPrice());
        BigDecimal lastClose = q.hasLastClosePrice() ? price(q.getLastClosePrice()) : null;
        Quote.SessionQuote pre = q.hasPreMarket() ? session(q.getPreMarket()) : null;
        Quote.SessionQuote after = q.hasAfterMarket() ? session(q.getAfterMarket()) : null;
        Quote.SessionQuote overnight = q.hasOvernight() ? session(q.getOvernight()) : null;

        BigDecimal price = cur;
        BigDecimal change = lastClose == null ? null : cur.subtract(lastClose);
        BigDecimal changeRate = lastClose == null || lastClose.signum() == 0 ? null
                : change.multiply(BigDecimal.valueOf(100)).divide(lastClose, 4, RoundingMode.HALF_UP);
        Quote.SessionQuote effective = switch (session) {
            case PRE -> pre;
            case AFTER -> after;
            case OVERNIGHT -> overnight != null ? overnight : after;
            default -> null;
        };
        // 基准价：常规时段是券商的"昨收"；盘前盘后夜盘券商是拿上一个常规收盘（curPrice 冻结值）算涨跌的，
        // 此时 lastClose 还停在上一个常规时段的前收，拿它当基准会算出完全不同的涨跌幅
        BigDecimal referenceClose = lastClose;
        if (effective != null && effective.price() != null && effective.price().signum() > 0) {
            price = effective.price();
            change = effective.change();
            changeRate = effective.changeRate();
            referenceClose = cur;
        }
        return new Quote(instrument, session, price, change, changeRate,
                price(q.getOpenPrice()), price(q.getHighPrice()), price(q.getLowPrice()), cur, lastClose, referenceClose,
                q.getVolume(), q.hasTurnover() ? BigDecimal.valueOf(q.getTurnover()).setScale(2, RoundingMode.HALF_UP) : null,
                pre, after, overnight, quoteTime(q), receivedAt, q.getIsSuspended());
    }

    static Quote.SessionQuote session(QotCommon.PreAfterMarketData d) {
        return new Quote.SessionQuote(price(d.getPrice()),
                d.hasChangeVal() ? price(d.getChangeVal()) : null,
                d.hasChangeRate() ? BigDecimal.valueOf(d.getChangeRate()).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros() : null,
                d.hasVolume() ? d.getVolume() : 0);
    }

    static Instant quoteTime(QotCommon.BasicQot q) {
        if (q.hasUpdateTimestamp() && q.getUpdateTimestamp() > 0) {
            return Instant.ofEpochMilli((long) (q.getUpdateTimestamp() * 1000));
        }
        try {
            return LocalDateTime.parse(q.getUpdateTime(), TIME).atZone(ET).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static BigDecimal price(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    /** 由 getGlobalState 的 marketUS 名称判时段；拿不到时按美东时钟。 */
    public static MarketSession session(String marketStateName, ZonedDateTime nowEt) {
        if (marketStateName != null) {
            switch (marketStateName) {
                case "PreMarketBegin", "PreMarketEnd" -> {
                    return MarketSession.PRE;
                }
                case "Morning", "Afternoon", "Rest" -> {
                    return MarketSession.RTH;
                }
                case "AfterHoursBegin" -> {
                    return MarketSession.AFTER;
                }
                case "NightOpen" -> {
                    return MarketSession.OVERNIGHT;
                }
                case "AfterHoursEnd", "NightEnd", "Closed", "Auction", "WaitingOpen", "FUTU_SWITCH_DATE" -> {
                    return MarketSession.CLOSED;
                }
                default -> {
                    // 未知名称回落到时钟
                }
            }
        }
        return byClock(nowEt == null ? ZonedDateTime.now(ET) : nowEt.withZoneSameInstant(ET));
    }

    static MarketSession byClock(ZonedDateTime et) {
        if (et.getDayOfWeek().getValue() >= 6) {
            return MarketSession.CLOSED;
        }
        LocalTime t = et.toLocalTime();
        if (!t.isBefore(LocalTime.of(4, 0)) && t.isBefore(LocalTime.of(9, 30))) {
            return MarketSession.PRE;
        }
        if (!t.isBefore(LocalTime.of(9, 30)) && t.isBefore(LocalTime.of(16, 0))) {
            return MarketSession.RTH;
        }
        if (!t.isBefore(LocalTime.of(16, 0)) && t.isBefore(LocalTime.of(20, 0))) {
            return MarketSession.AFTER;
        }
        return MarketSession.CLOSED;
    }
}
