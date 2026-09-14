package org.jdkxx.trader.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 前端深链接回退。
 *
 * <p>前端用 history 路由：从首页点进 {@code /account} 由浏览器内的路由切换，不发请求；
 * 直接打开或刷新 {@code /account} 时请求落到后端，没有映射就是 404（2.0.0 及以前如此）。
 * 这里把"单段、不含点、不是 api / actuator / error"的路径转发给 index.html，由前端路由接管。
 * 带点的是静态文件（{@code /assets/x.js}、{@code /favicon.ico}），照常走静态资源。
 *
 * <p>前端路由都是单段路径；{@code SpaForwardControllerTest} 逐个核对 {@code router/index.ts}，新增多段路由时会失败提醒。
 */
@Controller
public class SpaForwardController {

    /** 单段、不含点、不是 api / actuator / error。 */
    static final String PATH = "/{path:^(?!api$|actuator$|error$)[^.]+$}";

    @GetMapping(PATH)
    public String forward() {
        return "forward:/index.html";
    }
}
