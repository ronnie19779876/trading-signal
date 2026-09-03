package org.jdkxx.trader.gateway.futu;

import com.futu.openapi.FTAPI;

/**
 * 富途 SDK 的进程级初始化只能做一次。
 */
final class FutuApi {

    private static boolean initialised;

    private FutuApi() {
    }

    static synchronized void ensureInit() {
        if (!initialised) {
            FTAPI.init();
            initialised = true;
        }
    }

    static synchronized void shutdown() {
        if (initialised) {
            try {
                FTAPI.unInit();
            } finally {
                initialised = false;
            }
        }
    }
}
