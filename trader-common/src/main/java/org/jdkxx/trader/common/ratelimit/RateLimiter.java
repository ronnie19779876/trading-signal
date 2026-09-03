package org.jdkxx.trader.common.ratelimit;

/**
 * 限流器。调用方永远是我们自己的服务线程（绝不是 SDK 的回调线程），所以允许 {@link #acquire()} 短暂阻塞。
 */
public interface RateLimiter {

    /** 取得一个许可；需要等待时阻塞，超过实现设定的最长等待则抛 {@link RateLimitExceededException}。 */
    void acquire();

    /** 不阻塞：拿得到就拿，拿不到返回 false。 */
    boolean tryAcquire();
}
