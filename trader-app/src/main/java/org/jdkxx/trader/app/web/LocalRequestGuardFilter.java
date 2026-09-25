package org.jdkxx.trader.app.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * 本机请求防护。接口没有鉴权，敢这样做的前提是只监听回环地址——但回环挡不住<b>本机浏览器</b>：
 * <ul>
 *   <li>跨站写：任意网页都能向 127.0.0.1 发不带自定义头的 POST（表单、text/plain 不触发 CORS 预检），
 *       开着到生产的隧道时就能 POST /actuator/shutdown、/api/account/holdings/sync?apply=true；</li>
 *   <li>DNS rebinding：攻击者的域名解析到 127.0.0.1 后，页面能读到净值与持仓。</li>
 * </ul>
 * 所以：Host 的主机名只认回环（端口不限，隧道的本地端口可以与服务端口不同）；
 * 非 GET/HEAD/OPTIONS 必须带 {@value #CLIENT_HEADER} 头——跨站页面要加自定义头就得先过 CORS 预检，而本服务不放行任何跨域。
 * GET 一般不要求这个头：浏览器的 EventSource（实时报价流）发不了自定义头。
 * <b>但「GET 不改状态」并非全都成立</b>：{@code GET /api/account/live} 会真的向盈透发起常驻订阅
 * 并刷新闲置计时，是一个可被跨站 GET 触发的副作用（2026-09-25 全项目审查发现）。
 * 这类 GET 列进 {@link #SIDE_EFFECTING_GETS}，和写操作一样要带头。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalRequestGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalRequestGuardFilter.class);

    public static final String CLIENT_HEADER = "X-Trader-Client";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    /**
     * 有副作用的 GET：和写操作一样要带 {@value #CLIENT_HEADER}。
     * {@code /api/account/live} 第一次读会向盈透发起常驻订阅，每次读还会刷新「5 分钟没人读就退订」的计时。
     * 新增这类接口时记得加进来——或者干脆别把副作用放在 GET 上。
     */
    private static final Set<String> SIDE_EFFECTING_GETS = Set.of("/api/account/live");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = hostName(request.getHeader("Host"));
        if (host == null) {
            host = request.getServerName() == null ? "" : request.getServerName().toLowerCase(Locale.ROOT);
        }
        if (!LOOPBACK_HOSTS.contains(host)) {
            reject(request, response, "只接受经回环地址访问（Host 须为 localhost / 127.0.0.1 / [::1]）");
            return;
        }
        String client = request.getHeader(CLIENT_HEADER);
        boolean needsHeader = !SAFE_METHODS.contains(request.getMethod())
                || SIDE_EFFECTING_GETS.contains(request.getRequestURI());
        if (needsHeader && (client == null || client.isBlank())) {
            reject(request, response, "写操作与有副作用的读须带请求头 " + CLIENT_HEADER
                    + "（前端、脚本与 Postman 集合已带；手工 curl 加 -H '" + CLIENT_HEADER + ": cli'）");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Host 头去掉端口后的主机名（小写，IPv6 保留方括号）；没有 Host 头返回 null。 */
    static String hostName(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return null;
        }
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end < 0 ? h : h.substring(0, end + 1);
        }
        int colon = h.indexOf(':');
        return colon < 0 ? h : h.substring(0, colon);
    }

    private static void reject(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        log.warn("拒绝 {} {}：{}", request.getMethod(), request.getRequestURI(), message);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"REQUEST_REJECTED\",\"message\":\"" + message.replace("\"", "'") + "\"}");
    }
}
