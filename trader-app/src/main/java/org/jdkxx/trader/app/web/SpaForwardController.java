package org.jdkxx.trader.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Fallback for frontend deep links.
 *
 * <p>The frontend uses history-mode routing: clicking from the home page to {@code /account} is a
 * route change inside the browser and sends no request; opening or refreshing {@code /account}
 * directly sends the request to the backend, and without a mapping that is a 404 (this was the case
 * up to and including 2.0.0). This controller forwards paths that are "a single segment, contain no
 * dot, and are not api / actuator / error" to index.html so the frontend router takes over. Paths
 * with a dot are static files ({@code /assets/x.js}, {@code /favicon.ico}) and are served as static
 * resources as usual.
 *
 * <p>All frontend routes are single-segment paths; {@code SpaForwardControllerTest} checks every
 * route in {@code router/index.ts} and fails as a reminder when a multi-segment route is added.
 */
@Controller
public class SpaForwardController {

  /** Single segment, no dot, not api / actuator / error. */
  static final String PATH = "/{path:^(?!api$|actuator$|error$)[^.]+$}";

  @GetMapping(PATH)
  public String forward() {
    return "forward:/index.html";
  }
}
