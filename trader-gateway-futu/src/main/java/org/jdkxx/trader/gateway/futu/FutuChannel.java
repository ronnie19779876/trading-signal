package org.jdkxx.trader.gateway.futu;

import com.futu.openapi.FTAPI_Conn;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.FTAPI_Conn_Trd;
import com.futu.openapi.FTSPI_Conn;
import com.futu.openapi.FTSPI_Qot;
import com.futu.openapi.FTSPI_Trd;
import com.futu.openapi.pb.GetGlobalState;
import com.futu.openapi.pb.Notify;
import com.futu.openapi.pb.QotGetKL;
import com.futu.openapi.pb.QotGetCompanyProfile;
import com.futu.openapi.pb.QotGetFinancialsStatements;
import com.futu.openapi.pb.QotGetSecuritySnapshot;
import com.futu.openapi.pb.QotGetSubInfo;
import com.futu.openapi.pb.QotUpdateBasicQot;
import com.futu.openapi.pb.QotGetStaticInfo;
import com.futu.openapi.pb.QotRequestHistoryKL;
import com.futu.openapi.pb.QotRequestHistoryKLQuota;
import com.futu.openapi.pb.QotRequestRehab;
import com.futu.openapi.pb.QotRequestTradeDate;
import com.futu.openapi.pb.QotSub;
import com.futu.openapi.pb.TrdGetAccList;
import org.jdkxx.trader.common.ratelimit.RateLimiter;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.gateway.GatewayException;
import org.jdkxx.trader.gateway.NotConnectedException;
import org.jdkxx.trader.gateway.support.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * 到 OpenD 的一条连接：行情（QOT）或交易（TRD）。每次 open() 新建 SDK 连接对象（不复用 close 过的实例）。
 * 就绪 = onInitConnect errCode==0；断线 = onDisconnect。
 *
 * <p>探测：QOT 用 getGlobalState（顺带拿到 OpenD 登录与市场状态）；TRD 没有专门的探测接口，
 * 用只读的 getAccList（限频 10/30s，心跳 30s 一次远低于此）。
 */
final class FutuChannel implements Transport {

    private static final Logger log = LoggerFactory.getLogger(FutuChannel.class);

    enum Kind {
        QOT("行情"), TRD("交易");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    private final Kind kind;
    private final FutuProperties props;
    private final FutuReplyRegistry registry;
    private final Function<String, RateLimiter> limits;
    private volatile Consumer<String> closedHandler = reason -> { };
    private volatile Consumer<GetGlobalState.S2C> globalStateHandler = s -> { };
    private volatile Consumer<QotUpdateBasicQot.Response> basicQuoteHandler = r -> { };
    private volatile Session session;

    private static final class Session {
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final AtomicBoolean closed = new AtomicBoolean();
        volatile FTAPI_Conn conn;
        volatile boolean intentional;
    }

    private final FTSPI_Conn connSpi = new FTSPI_Conn() {
        @Override
        public void onInitConnect(FTAPI_Conn client, long errCode, String desc) {
            Session s = session;
            if (s == null || client != s.conn) {
                return;
            }
            if (errCode == 0) {
                s.ready.complete(null);
            } else {
                s.ready.completeExceptionally(new GatewayException(Broker.FUTU, (int) errCode,
                        kind.label + "通道连接 OpenD 失败：" + desc, true));
            }
        }

        @Override
        public void onDisconnect(FTAPI_Conn client, long errCode) {
            Session s = session;
            if (s == null || client != s.conn) {
                return;
            }
            sessionEnded(s, kind.label + "通道被 OpenD 断开（errCode=" + errCode + "）");
        }
    };

    private final FTSPI_Qot qotSpi = new FTSPI_Qot() {
        @Override
        public void onReply_GetGlobalState(FTAPI_Conn client, int nSerialNo, GetGlobalState.Response rsp) {
            registry.onReply(nSerialNo, rsp);
        }

        @Override
        public void onPush_Notify(FTAPI_Conn client, Notify.Response rsp) {
            log.info("OpenD 通知：type={}", rsp.hasS2C() ? rsp.getS2C().getType() : -1);
        }

        @Override
        public void onReply_Sub(FTAPI_Conn client, int nSerialNo, QotSub.Response rsp) {
            registry.onReply(nSerialNo, rsp);
        }

        @Override
        public void onReply_GetKL(FTAPI_Conn client, int nSerialNo, QotGetKL.Response rsp) {
            registry.onReply(nSerialNo, rsp);
        }

        @Override
        public void onReply_RequestHistoryKL(FTAPI_Conn client, int nSerialNo, QotRequestHistoryKL.Response rsp) {
            registry.onReply(nSerialNo, rsp);
        }

        @Override
        public void onReply_RequestHistoryKLQuota(FTAPI_Conn client, int nSerialNo, QotRequestHistoryKLQuota.Response rsp) {
            registry.onReply(nSerialNo, rsp);
        }

        @Override
        public void onReply_RequestRehab(FTAPI_Conn client, int nSerialNo, QotRequestRehab.Response rsp) {
            registry.onReply(nSerialNo, rsp);
        }

        @Override
        public void onReply_RequestTradeDate(FTAPI_Conn client, int nSerialNo, QotRequestTradeDate.Response rsp) {
            registry.onReply(nSerialNo, rsp);
        }

        @Override
        public void onReply_GetStaticInfo(FTAPI_Conn client, int nSerialNo, QotGetStaticInfo.Response rsp) {
            registry.onReply(nSerialNo, rsp);
        }

        @Override
        public void onReply_GetSecuritySnapshot(FTAPI_Conn client, int nSerialNo, QotGetSecuritySnapshot.Response rsp) {
            registry.onReply(nSerialNo, rsp);
        }

        @Override
        public void onReply_GetFinancialsStatements(FTAPI_Conn client, int nSerialNo, QotGetFinancialsStatements.Response rsp) {
            registry.onReply(nSerialNo, rsp);
        }

        @Override
        public void onReply_GetCompanyProfile(FTAPI_Conn client, int nSerialNo, QotGetCompanyProfile.Response rsp) {
            registry.onReply(nSerialNo, rsp);
        }

        @Override
        public void onReply_GetSubInfo(FTAPI_Conn client, int nSerialNo, QotGetSubInfo.Response rsp) {
            registry.onReply(nSerialNo, rsp);
        }

        /** 基础报价推送：SDK 线程上只做转交。 */
        @Override
        public void onPush_UpdateBasicQuote(FTAPI_Conn client, QotUpdateBasicQot.Response rsp) {
            basicQuoteHandler.accept(rsp);
        }
    };

    private final FTSPI_Trd trdSpi = new FTSPI_Trd() {
        @Override
        public void onReply_GetAccList(FTAPI_Conn client, int nSerialNo, TrdGetAccList.Response rsp) {
            registry.onReply(nSerialNo, rsp);
        }
    };

    FutuChannel(Kind kind, FutuProperties props, FutuReplyRegistry registry, Function<String, RateLimiter> limits) {
        this.kind = kind;
        this.props = props;
        this.registry = registry;
        this.limits = limits;
    }

    Kind kind() {
        return kind;
    }

    void onClosed(Consumer<String> handler) {
        this.closedHandler = handler;
    }

    void onGlobalState(Consumer<GetGlobalState.S2C> handler) {
        this.globalStateHandler = handler;
    }

    void onBasicQuote(Consumer<QotUpdateBasicQot.Response> handler) {
        this.basicQuoteHandler = handler;
    }

    // ------------------------------------------------------------------ Transport

    @Override
    public CompletableFuture<Void> open() {
        FutuApi.ensureInit();
        Session s = new Session();
        FTAPI_Conn conn = kind == Kind.QOT ? new FTAPI_Conn_Qot() : new FTAPI_Conn_Trd();
        conn.setClientInfo(props.clientInfo(), 1);
        conn.setConnSpi(connSpi);
        if (kind == Kind.QOT) {
            ((FTAPI_Conn_Qot) conn).setQotSpi(qotSpi);
        } else {
            ((FTAPI_Conn_Trd) conn).setTrdSpi(trdSpi);
        }
        s.conn = conn;
        session = s;
        try {
            if (props.encrypt()) {
                conn.setRSAPrivateKey(Files.readString(Path.of(props.rsaPrivateKeyFile()), StandardCharsets.UTF_8));
            }
            if (!conn.initConnect(props.host(), props.port(), props.encrypt())) {
                s.ready.completeExceptionally(new GatewayException(Broker.FUTU, 0,
                        kind.label + "通道发起连接失败（OpenD 未运行、端口或隧道不对）", true));
            }
        } catch (IOException e) {
            s.ready.completeExceptionally(new GatewayException(Broker.FUTU, 0, "读取 RSA 私钥失败：" + e.getMessage(), false, e));
        } catch (RuntimeException e) {
            s.ready.completeExceptionally(new GatewayException(Broker.FUTU, 0, kind.label + "通道建连异常：" + e, true, e));
        }
        return s.ready;
    }

    @Override
    public void close() {
        Session s = session;
        if (s == null) {
            return;
        }
        s.intentional = true;
        try {
            s.conn.close();
        } catch (RuntimeException e) {
            log.warn("关闭{}通道出错：{}", kind.label, e.toString());
        }
        sessionEnded(s, "主动关闭");
    }

    private void sessionEnded(Session s, String reason) {
        if (!s.closed.compareAndSet(false, true)) {
            return;
        }
        registry.failAll(new NotConnectedException(Broker.FUTU, reason));
        if (!s.ready.isDone()) {
            s.ready.completeExceptionally(new GatewayException(Broker.FUTU, 0, reason, true));
        }
        if (!s.intentional && session == s) {
            closedHandler.accept(reason);
        }
    }

    @Override
    public CompletableFuture<Boolean> probe() {
        return kind == Kind.QOT
                ? globalState().thenApply(s -> true)
                : accList().thenApply(r -> true);
    }

    // ------------------------------------------------------------------ 请求

    boolean isConnected() {
        Session s = session;
        return s != null && s.ready.isDone() && !s.ready.isCompletedExceptionally() && !s.closed.get();
    }

    CompletableFuture<GetGlobalState.S2C> globalState() {
        if (kind != Kind.QOT) {
            return CompletableFuture.failedFuture(new IllegalStateException("getGlobalState 只在行情通道上可用"));
        }
        FTAPI_Conn_Qot qot = (FTAPI_Conn_Qot) current();
        if (qot == null) {
            return CompletableFuture.failedFuture(new NotConnectedException(Broker.FUTU, kind.label + "通道未连接"));
        }
        GetGlobalState.Request req = GetGlobalState.Request.newBuilder()
                .setC2S(GetGlobalState.C2S.newBuilder().setUserID(0))
                .build();
        return registry.call("getGlobalState", GetGlobalState.Response.class, () -> {
            limits.apply("get-global-state").acquire();
            return qot.getGlobalState(req);
        }).thenApply(rsp -> {
            GetGlobalState.S2C s2c = rsp.getS2C();
            globalStateHandler.accept(s2c);
            return s2c;
        });
    }

    CompletableFuture<TrdGetAccList.Response> accList() {
        if (kind != Kind.TRD) {
            return CompletableFuture.failedFuture(new IllegalStateException("getAccList 只在交易通道上可用"));
        }
        FTAPI_Conn_Trd trd = (FTAPI_Conn_Trd) current();
        if (trd == null) {
            return CompletableFuture.failedFuture(new NotConnectedException(Broker.FUTU, kind.label + "通道未连接"));
        }
        TrdGetAccList.Request req = TrdGetAccList.Request.newBuilder()
                .setC2S(TrdGetAccList.C2S.newBuilder().setUserID(0).setNeedGeneralSecAccount(true))
                .build();
        return registry.call("getAccList", TrdGetAccList.Response.class, () -> {
            limits.apply("get-acc-list").acquire();
            return trd.getAccList(req);
        });
    }

    /**
     * 行情通道上的通用请求：先过该接口的限流器，再发送，回复按序列号关联。
     *
     * @param limitName 限频名（见 FutuProperties.DEFAULT_LIMITS）
     */
    <R> CompletableFuture<R> qotCall(String limitName, String what, Class<R> type, ToIntFunction<FTAPI_Conn_Qot> send) {
        if (kind != Kind.QOT) {
            return CompletableFuture.failedFuture(new IllegalStateException(what + " 只在行情通道上可用"));
        }
        FTAPI_Conn_Qot qot = (FTAPI_Conn_Qot) current();
        if (qot == null) {
            return CompletableFuture.failedFuture(new NotConnectedException(Broker.FUTU, kind.label + "通道未连接"));
        }
        return registry.call(what, type, () -> {
            limits.apply(limitName).acquire();
            return send.applyAsInt(qot);
        });
    }

    private FTAPI_Conn current() {
        Session s = session;
        return isConnected() ? s.conn : null;
    }
}
