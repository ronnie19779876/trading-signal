package org.jdkxx.trader.gateway.futu;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每个富途响应都带 retType（0 成功 / -1 失败 / -100 超时 / -400 未知）、retMsg、errCode，
 * 但生成类之间没有公共接口，这里用缓存过的反射统一读取。
 */
record FutuReplyStatus(int retType, int errCode, String retMsg) {

    private static final Map<Class<?>, Method[]> ACCESSORS = new ConcurrentHashMap<>();

    boolean ok() {
        return retType == 0;
    }

    /** 优先用 errCode，没有时用 retType 充当错误码。 */
    int code() {
        return errCode != 0 ? errCode : retType;
    }

    static FutuReplyStatus of(Object response) {
        Method[] m = ACCESSORS.computeIfAbsent(response.getClass(), FutuReplyStatus::lookup);
        try {
            int retType = (int) m[0].invoke(response);
            String msg = m[1] == null ? "" : String.valueOf(m[1].invoke(response));
            int errCode = m[2] == null ? 0 : (int) m[2].invoke(response);
            return new FutuReplyStatus(retType, errCode, msg);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取富途响应状态失败：" + response.getClass().getName(), e);
        }
    }

    private static Method[] lookup(Class<?> type) {
        try {
            Method retType = type.getMethod("getRetType");
            Method retMsg = optional(type, "getRetMsg");
            Method errCode = optional(type, "getErrCode");
            return new Method[] {retType, retMsg, errCode};
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("不是富途响应类型（没有 getRetType）：" + type.getName(), e);
        }
    }

    private static Method optional(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
