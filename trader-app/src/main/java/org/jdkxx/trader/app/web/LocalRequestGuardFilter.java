package org.jdkxx.trader.app.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Local-request guard. The API has no authentication; that is only acceptable because the server
 * listens on the loopback address only. Loopback, however, does not protect against a <b>browser
 * running on the same machine</b>:
 *
 * <ul>
 *   <li>Cross-site writes: any web page can send a POST without custom headers to 127.0.0.1 (HTML
 *       forms and text/plain bodies do not trigger a CORS preflight). With a tunnel to production
 *       open, that page could POST /actuator/shutdown or /api/account/holdings/sync?apply=true;
 *   <li>DNS rebinding: once an attacker's domain resolves to 127.0.0.1, the page can read net
 *       liquidation and positions.
 * </ul>
 *
 * Therefore: the host name in the Host header must be a loopback name (any port is accepted,
 * because the local end of a tunnel may use a different port than the server); every method other
 * than GET/HEAD/OPTIONS must carry the {@value #CLIENT_HEADER} header. A cross-site page that wants
 * to add a custom header has to pass a CORS preflight first, and this service allows no
 * cross-origin access at all. GET normally does not require the header, because the browser's
 * EventSource (the real-time quote stream) cannot send custom headers. <b>But "GET has no side
 * effects" is not universally true here</b>: {@code GET /api/account/live} really starts a resident
 * subscription at IBKR and refreshes the idle timer, which is a side effect a cross-site GET can
 * trigger (found in the 2026-09-25 full-project review). Such GETs are listed in {@link
 * #SIDE_EFFECTING_GETS} and need the header just like writes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalRequestGuardFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(LocalRequestGuardFilter.class);

  public static final String CLIENT_HEADER = "X-Trader-Client";
  private static final Set<String> LOOPBACK_HOSTS =
      Set.of("localhost", "127.0.0.1", "[::1]", "::1");
  private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

  /**
   * GETs with side effects: like writes, they must carry {@value #CLIENT_HEADER}. The first read of
   * {@code /api/account/live} starts a resident subscription at IBKR, and every read refreshes the
   * "unsubscribe after 5 minutes without readers" timer. Remember to add new endpoints of this kind
   * here, or better, do not put side effects on a GET at all.
   *
   * <p>Matched against the <b>normalized</b> path ({@link #lookupPath}), never the raw {@code
   * getRequestURI()}: Spring MVC routes on the decoded path with matrix parameters removed, so
   * before 3.5.2 {@code /api/account/live;x=1} or {@code /api/account/l%69ve} reached the handler
   * while skipping this check (found in the 2026-10-31 review). The handler additionally declares
   * the header as required, so a future mismatch here still cannot trigger the side effect.
   */
  private static final Set<String> SIDE_EFFECTING_GETS = Set.of("/api/account/live");

  /**
   * Same normalization Spring MVC applies before routing: percent-decoding, removal of {@code ;}
   * path parameters (including {@code ;jsessionid}) and collapsing of duplicate slashes.
   */
  private static final UrlPathHelper PATHS = new UrlPathHelper();

  static {
    PATHS.setUrlDecode(true);
    PATHS.setRemoveSemicolonContent(true);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    // Host check first: it is what defeats DNS rebinding (the rebinding page sends its own domain
    // as Host).
    String host = hostName(request.getHeader("Host"));
    if (host == null) {
      // No Host header (HTTP/1.0): fall back to the server name the connector resolved.
      host =
          request.getServerName() == null ? "" : request.getServerName().toLowerCase(Locale.ROOT);
    }
    if (!LOOPBACK_HOSTS.contains(host)) {
      reject(request, response, "只接受经回环地址访问（Host 须为 localhost / 127.0.0.1 / [::1]）");
      return;
    }
    // Custom-header check: it is what defeats cross-site writes (a custom header forces a CORS
    // preflight, which this service never approves). The header value itself is arbitrary.
    String client = request.getHeader(CLIENT_HEADER);
    boolean needsHeader =
        !SAFE_METHODS.contains(request.getMethod())
            || SIDE_EFFECTING_GETS.contains(lookupPath(request));
    if (needsHeader && (client == null || client.isBlank())) {
      reject(
          request,
          response,
          "写操作与有副作用的读须带请求头 "
              + CLIENT_HEADER
              + "（前端、脚本与 Postman 集合已带；手工 curl 加 -H '"
              + CLIENT_HEADER
              + ": cli'）");
      return;
    }
    chain.doFilter(request, response);
  }

  /**
   * The application-relative path as Spring MVC sees it, with a trailing slash dropped so that
   * {@code /api/account/live/} is treated like {@code /api/account/live} even if trailing-slash
   * matching is ever turned back on.
   */
  static String lookupPath(HttpServletRequest request) {
    String path = PATHS.getPathWithinApplication(request);
    return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
  }

  /**
   * Host name from the Host header with the port removed (lower-cased; IPv6 keeps its square
   * brackets). Returns null when there is no Host header. Anything that is not exactly a loopback
   * name (a trailing dot, userinfo, extra characters) simply fails the later lookup, so the parser
   * does not have to be strict.
   */
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

  private static void reject(
      HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
    log.warn("拒绝 {} {}：{}", request.getMethod(), request.getRequestURI(), message);
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    // The message is always one of the constants above (no user input), so a quote replacement is
    // enough.
    response
        .getWriter()
        .write(
            "{\"code\":\"REQUEST_REJECTED\",\"message\":\"" + message.replace("\"", "'") + "\"}");
  }
}
