package org.jdkxx.trader.app.web;

import org.jdkxx.trader.core.system.SystemInfo;
import org.jdkxx.trader.core.system.SystemInfoService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * System information: version, environment, database, status of each gateway. The only data source
 * of the frontend's skeleton page.
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
