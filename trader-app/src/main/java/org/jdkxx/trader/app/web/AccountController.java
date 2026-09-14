package org.jdkxx.trader.app.web;

import org.jdkxx.trader.core.account.AccountFacade;
import org.jdkxx.trader.storage.account.AccountSnapshotRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 账户与持仓（第 3 期）：快照作业与查询。账户号只以脱敏形式出现。只在存储启用时装配。
 */
@RestController
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class AccountController {

    private final AccountFacade facade;

    public AccountController(AccountFacade facade) {
        this.facade = facade;
    }

    /** 账户快照作业。只能在快照窗口内拍（窗口外 409）；force=true 只在开发环境可用。 */
    @PostMapping("/api/account/snapshot")
    public Map<String, Object> snapshot(@RequestParam(defaultValue = "false") boolean force) {
        return Map.of("jobId", facade.snapshot("MANUAL", force));
    }

    @GetMapping("/api/account/snapshots/latest")
    public AccountFacade.SnapshotView latest() {
        return facade.latest().orElseThrow(() -> new NoSuchElementException("还没有账户快照"));
    }

    @GetMapping("/api/account/snapshots")
    public List<AccountSnapshotRow> snapshots(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(90) : from;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("from 不能晚于 to");
        }
        return facade.between(start, end);
    }
}
