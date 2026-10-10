package org.jdkxx.trader.app.web;

import org.jdkxx.trader.core.selection.BackfillPart;
import org.jdkxx.trader.core.selection.SelectionFacade;
import org.jdkxx.trader.core.selection.SelectionInputsService;
import org.jdkxx.trader.storage.selection.SelectionPriceSeamRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 量化选股（第 6 期，ARCHITECTURE §25）。独立功能，与入场信号的接口互不相干。只在存储启用时装配。
 */
@RestController
@RequestMapping("/api/selection")
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionController {

    private final SelectionFacade facade;

    public SelectionController(SelectionFacade facade) {
        this.facade = facade;
    }

    /**
     * 输入回补（异步作业）。part 取 prices / valuation / earnings / financials；
     * symbols 逗号分隔、缺省为全部范围；已完成的跳过，force=true 重取。
     */
    @PostMapping("/backfill")
    public Map<String, Object> backfill(@RequestParam String part, @RequestParam(required = false) String symbols,
                                        @RequestParam(defaultValue = "false") boolean force) {
        BackfillPart p = part(part);
        List<String> list = symbols == null || symbols.isBlank() ? List.of()
                : Arrays.stream(symbols.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return Map.of("jobId", facade.backfill("MANUAL", p, list, force));
    }

    /** 输入覆盖报告（6.1 验收用）：各部分状态、失败与空回清单、接缝、估值与快照一致性、公告日匹配、季报深度。 */
    @GetMapping("/inputs")
    public SelectionInputsService.Report inputs() {
        return facade.inputs();
    }

    /** 盈透与富途的接缝核对明细；failedOnly=true 只看不通过的。 */
    @GetMapping("/seams")
    public List<SelectionPriceSeamRepository.SeamRow> seams(@RequestParam(defaultValue = "false") boolean failedOnly) {
        return facade.seams(failedOnly);
    }

    private static BackfillPart part(String raw) {
        try {
            return BackfillPart.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("part 应为 prices / valuation / earnings / financials，收到：" + raw);
        }
    }
}
