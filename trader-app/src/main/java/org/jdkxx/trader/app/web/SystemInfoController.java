package org.jdkxx.trader.app.web;

import org.jdkxx.trader.core.system.SystemInfo;
import org.jdkxx.trader.core.system.SystemInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统信息：版本、环境、数据库、各网关状态。前端骨架页的唯一数据源。
 */
@RestController
@RequestMapping("/api/system")
public class SystemInfoController {

    private final SystemInfoService service;

    public SystemInfoController(SystemInfoService service) {
        this.service = service;
    }

    @GetMapping("/info")
    public SystemInfo info() {
        return service.current();
    }
}
