package org.jdkxx.trader.domain.valuation;

/**
 * 分部估值的情景。三个必须同时给：期权型业务（Robotaxi、Semi 这类）用单点估值没有意义，
 * 2026-09-19 对 TSLA 的实测里只动 Robotaxi 一项就能把 2030 股价从 $71 拉到 $385。
 */
public enum SotpScenario {
    BEAR, BASE, BULL
}
