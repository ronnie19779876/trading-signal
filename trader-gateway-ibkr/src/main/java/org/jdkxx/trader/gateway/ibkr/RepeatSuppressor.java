package org.jdkxx.trader.gateway.ibkr;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 同一错误码持续复现时的日志降频：首次照常记，之后按窗口汇总。
 * 断线重连期间同一个码每分钟一条会把日志淹掉（实测一次 25 小时的网关停机刷了 1500 多行）。
 */
final class RepeatSuppressor {

    /** 一次决策：要不要记、记几条被压掉的、距首次多久。 */
    record Decision(boolean log, long suppressed, Duration since) {
    }

    private final Duration window;
    private final Clock clock;

    private int lastKey = Integer.MIN_VALUE;
    private Instant firstAt;
    private Instant lastLoggedAt;
    private long suppressed;

    RepeatSuppressor(Duration window, Clock clock) {
        this.window = window;
        this.clock = clock;
    }

    /** 不同的 key 立刻重新开始计数；相同的 key 在窗口内压住，窗口到点放一条并带上被压掉的条数。 */
    synchronized Decision offer(int key) {
        Instant now = clock.instant();
        if (key != lastKey) {
            lastKey = key;
            firstAt = now;
            lastLoggedAt = now;
            suppressed = 0;
            return new Decision(true, 0, Duration.ZERO);
        }
        if (Duration.between(lastLoggedAt, now).compareTo(window) >= 0) {
            long n = suppressed;
            suppressed = 0;
            lastLoggedAt = now;
            return new Decision(true, n, Duration.between(firstAt, now));
        }
        suppressed++;
        return new Decision(false, suppressed, Duration.between(firstAt, now));
    }

    /** 连上以后清掉，下次故障从头记。 */
    synchronized void reset() {
        lastKey = Integer.MIN_VALUE;
        suppressed = 0;
    }
}
