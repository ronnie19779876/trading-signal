package org.jdkxx.trader.core.marketdata.quotes;

import org.jdkxx.trader.common.ratelimit.Sleeper;
import org.jdkxx.trader.core.marketdata.MarketDataProperties;
import org.jdkxx.trader.core.marketdata.bars.RotationRefresher;
import org.jdkxx.trader.core.marketdata.universe.UniverseScope;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.SubscriptionInfo;
import org.jdkxx.trader.gateway.GatewayListener;
import org.jdkxx.trader.gateway.MarketDataGateway;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 实时订阅的对账：期望集合 = 池 ∪ 持仓；与已订集合做差，新增订阅、多余反订阅（未满 1 分钟的延后）。
 * 触发点：网关连上（auto-subscribe）、重连、池增删、手工；轮转期间可暂停（释放额度）并在结束后恢复。
 * 断线时已订集合清空（券商侧订阅随连接消失）。
 */
public class QuoteSubscriptionService implements GatewayListener, RotationRefresher.QuotaCoordinator {

    private static final Logger log = LoggerFactory.getLogger(QuoteSubscriptionService.class);
    private static final int SUB_CHUNK = 100;

    public record Result(int desired, int subscribed, int added, int removed, int deferred, String error) {
    }

    public record Status(boolean enabled, boolean paused, int desired, int subscribed, int deferredUnsubscribe,
                         SubscriptionInfo quota, Instant lastReconcileAt, String lastError) {
    }

    private final MarketDataProperties.Realtime props;
    private final MarketDataGateway gateway;
    private final UniverseScope scope;
    private final QuoteCache cache;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Map<Instrument, Instant> subscribed = new LinkedHashMap<>();
    private volatile boolean paused;
    /** 当前的暂停是轮转发起的（手工暂停的不归轮转恢复）。 */
    private boolean pausedByRefresh;
    /** 轮转结束后是否重新订阅：自动订阅的实例，或轮转前本来就有订阅的。 */
    private boolean resubscribeAfterRefresh;
    private volatile Instant lastReconcileAt;
    private volatile String lastError;
    private volatile SubscriptionInfo lastQuota;

    public QuoteSubscriptionService(MarketDataProperties.Realtime props, MarketDataGateway gateway, UniverseScope scope,
                                    QuoteCache cache, Clock clock) {
        this(props, gateway, scope, cache, clock, Sleeper.REAL);
    }

    public QuoteSubscriptionService(MarketDataProperties.Realtime props, MarketDataGateway gateway, UniverseScope scope,
                                    QuoteCache cache, Clock clock, Sleeper sleeper) {
        this.props = props;
        this.gateway = gateway;
        this.scope = scope;
        this.cache = cache;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** 期望订阅的标的（富途已解析且未退市的池与持仓）。 */
    public Set<Instrument> desired() {
        Set<Instrument> out = new LinkedHashSet<>();
        for (InstrumentRow r : scope.poolAndHoldings()) {
            out.add(r.instrument());
        }
        return out;
    }

    public synchronized Result reconcile() {
        if (!props.enabled()) {
            return new Result(0, subscribed.size(), 0, 0, 0, "实时订阅未启用");
        }
        if (paused) {
            return new Result(desired().size(), subscribed.size(), 0, 0, 0, "已暂停");
        }
        Set<Instrument> want = desired();
        List<Instrument> toAdd = new ArrayList<>();
        for (Instrument i : want) {
            if (!subscribed.containsKey(i)) {
                toAdd.add(i);
            }
        }
        List<Instrument> toRemove = new ArrayList<>();
        int deferred = 0;
        Instant now = clock.instant();
        for (Map.Entry<Instrument, Instant> e : subscribed.entrySet()) {
            if (want.contains(e.getKey())) {
                continue;
            }
            if (Duration.between(e.getValue(), now).compareTo(props.unsubscribeMinAge()) >= 0) {
                toRemove.add(e.getKey());
            } else {
                deferred++;
            }
        }
        int added = 0;
        int removed = 0;
        String error = null;
        try {
            for (List<Instrument> chunk : chunks(toAdd)) {
                gateway.subscribeQuotes(chunk).get(30, TimeUnit.SECONDS);
                chunk.forEach(i -> subscribed.put(i, now));
                added += chunk.size();
            }
            for (List<Instrument> chunk : chunks(toRemove)) {
                gateway.unsubscribeQuotes(chunk).get(30, TimeUnit.SECONDS);
                chunk.forEach(subscribed::remove);
                cache.remove(chunk);
                removed += chunk.size();
            }
            refreshQuota();
        } catch (Exception e) {
            error = rootMessage(e);
            log.warn("实时订阅对账失败：{}", error);
        }
        lastReconcileAt = now;
        lastError = error;
        if (added > 0 || removed > 0) {
            log.info("实时订阅对账：期望 {}，已订 {}，新增 {}，反订阅 {}，延后 {}", want.size(), subscribed.size(), added, removed, deferred);
        }
        return new Result(want.size(), subscribed.size(), added, removed, deferred, error);
    }

    /** 暂停（手工）：反订阅满 1 分钟的，未满的延后（保持已订记录）；暂停期间不再对账订阅。 */
    public synchronized Result pause() {
        return pause(false);
    }

    /**
     * @param waitForYoung true = 未满 1 分钟的等到满了再反订阅（轮转前需要把额度全部释放），最多等 unsubscribe-min-age
     */
    public synchronized Result pause(boolean waitForYoung) {
        paused = true;
        Result r = unsubscribeAged("暂停");
        if (waitForYoung && !subscribed.isEmpty()) {
            Instant youngest = subscribed.values().stream().max(Instant::compareTo).orElse(clock.instant());
            Duration wait = props.unsubscribeMinAge().minus(Duration.between(youngest, clock.instant()));
            if (!wait.isNegative() && !wait.isZero()) {
                log.info("有 {} 个订阅未满 1 分钟，等待 {} 秒后再反订阅", subscribed.size(), wait.toSeconds());
                try {
                    sleeper.sleep(wait.plusSeconds(1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            r = unsubscribeAged("暂停");
        }
        cache.clear();
        return r;
    }

    public synchronized Result resume() {
        paused = false;
        return reconcile();
    }

    /** 反订阅已满 1 分钟的全部订阅；未满的留在已订集合里（deferred）。 */
    private Result unsubscribeAged(String why) {
        Instant now = clock.instant();
        List<Instrument> aged = new ArrayList<>();
        int deferred = 0;
        for (Map.Entry<Instrument, Instant> e : subscribed.entrySet()) {
            if (Duration.between(e.getValue(), now).compareTo(props.unsubscribeMinAge()) >= 0) {
                aged.add(e.getKey());
            } else {
                deferred++;
            }
        }
        int removed = 0;
        String error = null;
        try {
            for (List<Instrument> chunk : chunks(aged)) {
                gateway.unsubscribeQuotes(chunk).get(30, TimeUnit.SECONDS);
                chunk.forEach(subscribed::remove);
                removed += chunk.size();
            }
            refreshQuota();
        } catch (Exception e) {
            error = rootMessage(e);
            log.warn("{}时反订阅失败：{}", why, error);
        }
        lastError = error;
        return new Result(desired().size(), subscribed.size(), 0, removed, deferred, error);
    }

    public boolean paused() {
        return paused;
    }

    public synchronized Status status() {
        int deferred = 0;
        Set<Instrument> want = desired();
        for (Instrument i : subscribed.keySet()) {
            if (!want.contains(i)) {
                deferred++;
            }
        }
        return new Status(props.enabled(), paused, want.size(), subscribed.size(), deferred, lastQuota, lastReconcileAt, lastError);
    }

    public SubscriptionInfo refreshQuota() {
        try {
            lastQuota = gateway.subscriptionInfo().get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("订阅额度查询失败：{}", e.toString());
        }
        return lastQuota;
    }

    /**
     * 池变动后的对账（{@code PoolService.afterChange} 钩子）。
     *
     * <p>auto-subscribe=false 且当前没有任何订阅时不动：开发实例就是这种状态，此前池变动（手工加减池成员、持仓同步）
     * 直接调 {@link #reconcile()}，让开发实例也订阅实时报价，与生产同时订、占同一账户的双份额度。
     * 已经手工对账过（有订阅）的实例照常跟着池变，免得订阅集合与池不一致。
     */
    public Result onPoolChanged() {
        boolean hasSubscriptions;
        synchronized (this) {
            hasSubscriptions = !subscribed.isEmpty();
        }
        if (!props.autoSubscribe() && !hasSubscriptions) {
            log.info("标的池有变动；auto-subscribe=false 且当前没有订阅，不对账");
            return new Result(0, 0, 0, 0, 0, "auto-subscribe=false 且当前没有订阅，未对账");
        }
        return reconcile();
    }

    // ------------------------------------------------------------------ GatewayListener（富途）

    @Override
    public void onConnected(Broker broker, boolean reconnected) {
        if (broker != Broker.FUTU) {
            return;
        }
        synchronized (this) {
            subscribed.clear();   // 新连接上没有任何订阅
            cache.clear();
        }
        if (reconnected || props.autoSubscribe()) {
            Result r = reconcile();
            log.info("富途{}，实时订阅对账：{}", reconnected ? "重连" : "连上", r);
        }
    }

    @Override
    public void onDisconnected(Broker broker, String reason) {
        if (broker != Broker.FUTU) {
            return;
        }
        synchronized (this) {
            subscribed.clear();
        }
    }

    // ------------------------------------------------------------------ 轮转协调

    @Override
    public int beforeRefresh() {
        if (!props.enabled()) {
            return 0;
        }
        if (props.pauseDuringRefresh()) {
            Result r;
            synchronized (this) {
                if (paused) {
                    log.info("全量轮转开始；实时订阅已处于暂停，保持原状");
                    return 0;
                }
                resubscribeAfterRefresh = props.autoSubscribe() || !subscribed.isEmpty();
                pausedByRefresh = true;
                r = pause(true);
            }
            log.info("全量轮转开始，暂停实时订阅：{}", r);
            return 0;
        }
        SubscriptionInfo q = refreshQuota();
        return q == null ? 0 : Math.max(1, q.remainQuota() - props.reserveQuota());
    }

    /**
     * 只恢复轮转自己发起的暂停。不自动订阅、轮转前也没有订阅的实例（开发实例）只解除暂停、不对账：
     * 2.0.2 前这里无条件对账，开发实例每跑一次轮转就订阅一次实时报价，与生产同占一个账户的额度。
     */
    @Override
    public void afterRefresh() {
        boolean resubscribe;
        synchronized (this) {
            if (!pausedByRefresh) {
                return;
            }
            pausedByRefresh = false;
            resubscribe = resubscribeAfterRefresh;
            if (!resubscribe) {
                paused = false;
            }
        }
        if (!resubscribe) {
            log.info("全量轮转结束，解除暂停；auto-subscribe=false 且轮转前没有订阅，不对账");
            return;
        }
        Result r = resume();
        log.info("全量轮转结束，恢复实时订阅：{}", r);
    }

    private static List<List<Instrument>> chunks(List<Instrument> list) {
        List<List<Instrument>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += SUB_CHUNK) {
            out.add(list.subList(i, Math.min(list.size(), i + SUB_CHUNK)));
        }
        return out;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}
