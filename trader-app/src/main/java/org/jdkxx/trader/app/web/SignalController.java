package org.jdkxx.trader.app.web;

import org.jdkxx.trader.core.signal.SentinelService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 入场信号（第 4 期）。目前只有只读计算：单日四门判定、区间回放。只在存储启用时装配。
 */
@RestController
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SignalController {

    private final SentinelService sentinel;

    public SignalController(SentinelService sentinel) {
        this.sentinel = sentinel;
    }

    /** 某只某天的四门判定过程（现场计算）。date 缺省取最近收盘落定的交易日。 */
    @GetMapping("/api/signals/evaluate/{symbol}")
    public SentinelService.Judgement evaluate(@PathVariable String symbol,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return sentinel.evaluate(symbol, date);
    }

    /** 区间回放：逐个交易日判定，按边沿与冷却标出信号（历史上没有 AI 结论）。默认最近一年，最长 21 年。 */
    @GetMapping("/api/signals/replay/{symbol}")
    public SentinelService.Replay replay(@PathVariable String symbol,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return sentinel.replay(symbol, from, to);
    }
}
