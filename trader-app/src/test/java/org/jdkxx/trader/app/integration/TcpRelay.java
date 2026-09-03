package org.jdkxx.trader.app.integration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 测试用的本地 TCP 中继：网关连中继，中继连真实网关。{@link #dropConnections()} 模拟断线，
 * {@link #setAccepting(boolean)} 模拟对端暂时不可达。
 */
final class TcpRelay implements AutoCloseable {

    private final String targetHost;
    private final int targetPort;
    private final ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "tcp-relay");
        t.setDaemon(true);
        return t;
    });
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean accepting = true;
    private volatile boolean closed;

    TcpRelay(String targetHost, int targetPort) throws IOException {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        pool.execute(this::acceptLoop);
    }

    int port() {
        return server.getLocalPort();
    }

    void setAccepting(boolean accepting) {
        this.accepting = accepting;
    }

    int activeConnections() {
        return sockets.size() / 2;
    }

    void dropConnections() {
        for (Socket s : sockets) {
            closeQuietly(s);
        }
        sockets.clear();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                return;
            }
            if (!accepting) {
                closeQuietly(client);
                continue;
            }
            try {
                Socket target = new Socket(targetHost, targetPort);
                sockets.add(client);
                sockets.add(target);
                pool.execute(() -> pipe(client, target));
                pool.execute(() -> pipe(target, client));
            } catch (IOException e) {
                closeQuietly(client);
            }
        }
    }

    private void pipe(Socket from, Socket to) {
        byte[] buf = new byte[8192];
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
            // 一端断开，关掉另一端
        } finally {
            closeQuietly(from);
            closeQuietly(to);
            sockets.remove(from);
            sockets.remove(to);
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        accepting = false;
        dropConnections();
        server.close();
        pool.shutdownNow();
    }
}
