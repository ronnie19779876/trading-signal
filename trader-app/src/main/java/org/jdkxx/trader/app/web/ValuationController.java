package org.jdkxx.trader.app.web;

import org.jdkxx.trader.core.marketdata.valuation.SotpBasisService;
import org.jdkxx.trader.core.marketdata.valuation.SotpService;
import org.jdkxx.trader.domain.valuation.SotpResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 分部估值（SOTP）。只读底座 + 手工假设的存取与试算，<b>不联动信号与纸面账本</b>。
 *
 * <p>券商只给合并报表，没有分部量价、没有一致预期，所以各业务线的假设必须由使用者自己填；
 * 本接口负责自动带入公司级底座、做算术、做一致性核对，不预测、不给建议。设计见 docs/ARCHITECTURE.md §21。
 */
@RestController
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class ValuationController {

    private final SotpService sotp;

    public ValuationController(SotpService sotp) {
        this.sotp = sotp;
    }

    /** 自动带入的公司级底座：现价、三种口径的股数、净现金拆解、TTM 营收与净利率、本益比基准、适用性。 */
    @GetMapping("/api/valuation/sotp/{symbol}/inputs")
    public SotpBasisService.Basis inputs(@PathVariable String symbol) {
        return sotp.inputs(symbol);
    }

    /** 该标的已存的方案（连同各自算出来的结果），按更新时间倒序。 */
    @GetMapping("/api/valuation/sotp/{symbol}")
    public List<SotpService.SavedModel> models(@PathVariable String symbol) {
        return sotp.models(symbol);
    }

    /** 试算，不落库。路径用 calc/{symbol} 两段，和上面单段的 {symbol} 不会撞。 */
    @PostMapping("/api/valuation/sotp/calc/{symbol}")
    public SotpResult calc(@PathVariable String symbol, @RequestBody SotpService.ModelRequest request) {
        return sotp.calc(symbol, request);
    }

    /** 保存 / 更新一套方案（按名称覆盖）。 */
    @PostMapping("/api/valuation/sotp/{symbol}")
    public SotpService.SavedModel save(@PathVariable String symbol, @RequestBody SotpService.ModelRequest request) {
        return sotp.save(symbol, request);
    }

    @DeleteMapping("/api/valuation/sotp/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        sotp.delete(id);
        return Map.of("deleted", id);
    }
}
