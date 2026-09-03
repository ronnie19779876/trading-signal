package org.jdkxx.trader.gateway.ibkr;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接期间收集到的可公开事实：server version、连接时间、受管账户数、各数据农场状态、连通性。
 * 账户号本身只在内存里，不进 snapshot。
 */
final class IbkrFacts {

    private volatile int serverVersion;
    private volatile String connectionTime = "";
    private volatile List<String> managedAccounts = List.of();
    private volatile int nextOrderId = -1;
    private volatile String connectivity = "";
    private volatile String lastSystemMessage = "";
    private final ConcurrentHashMap<String, String> farms = new ConcurrentHashMap<>();

    void serverVersion(int v) {
        serverVersion = v;
    }

    void connectionTime(String t) {
        connectionTime = t == null ? "" : t;
    }

    void managedAccounts(List<String> accounts) {
        managedAccounts = List.copyOf(accounts);
    }

    List<String> managedAccounts() {
        return managedAccounts;
    }

    void nextOrderId(int id) {
        nextOrderId = id;
    }

    void connectivity(String c) {
        connectivity = c;
    }

    void lastSystemMessage(String m) {
        lastSystemMessage = m;
    }

    void farm(String name, String state) {
        farms.put(name, state);
    }

    void reset() {
        farms.clear();
        connectivity = "";
        nextOrderId = -1;
    }

    Map<String, String> snapshot() {
        Map<String, String> m = new TreeMap<>();
        if (serverVersion > 0) {
            m.put("serverVersion", Integer.toString(serverVersion));
        }
        if (!connectionTime.isBlank()) {
            m.put("connectionTime", connectionTime);
        }
        m.put("accounts", Integer.toString(managedAccounts.size()));
        if (nextOrderId >= 0) {
            m.put("nextOrderId", Integer.toString(nextOrderId));
        }
        if (!connectivity.isBlank()) {
            m.put("connectivity", connectivity);
        }
        if (!lastSystemMessage.isBlank()) {
            m.put("lastSystemMessage", lastSystemMessage);
        }
        farms.forEach((k, v) -> m.put("farm." + k, v));
        return m;
    }
}
