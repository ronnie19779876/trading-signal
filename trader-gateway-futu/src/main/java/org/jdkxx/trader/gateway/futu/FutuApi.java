package org.jdkxx.trader.gateway.futu;

import com.futu.openapi.FTAPI;

/** Process-wide initialisation of the Futu SDK, which may only be performed once. */
final class FutuApi {

  private static boolean initialised;

  private FutuApi() {}

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
