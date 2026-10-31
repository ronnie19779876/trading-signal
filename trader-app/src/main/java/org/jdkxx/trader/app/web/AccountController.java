package org.jdkxx.trader.app.web;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.jdkxx.trader.core.account.AccountAuditService;
import org.jdkxx.trader.core.account.AccountFacade;
import org.jdkxx.trader.core.account.HoldingReviewService;
import org.jdkxx.trader.core.account.HoldingSyncService;
import org.jdkxx.trader.core.account.LiveAccountService;
import org.jdkxx.trader.core.marketdata.audit.BarAuditService;
import org.jdkxx.trader.storage.account.AccountSnapshotRow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Account and positions (phase 3): snapshot job, snapshot queries, holding sync, account audit;
 * real-time account since 3.0.2; holding risk panel since 3.5.0. Account numbers only ever appear
 * masked. Only wired when storage is enabled.
 */
@RestController
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class AccountController {

  private final AccountFacade facade;
  private final HoldingSyncService holdingSync;
  private final AccountAuditService audit;
  private final LiveAccountService live;
  private final HoldingReviewService review;

  public AccountController(
      AccountFacade facade,
      HoldingSyncService holdingSync,
      AccountAuditService audit,
      LiveAccountService live,
      HoldingReviewService review) {
    this.facade = facade;
    this.holdingSync = holdingSync;
    this.audit = audit;
    this.live = live;
    this.review = review;
  }

  /**
   * Real-time account: funds, P&amp;L, positions (IBKR resident subscription; reads memory only).
   * The first read starts the subscription (WARMING); it unsubscribes automatically after 5 minutes
   * without readers. Each part carries its own update time: funds are pushed about every 3 minutes,
   * P&amp;L and per-position market value are pushed within seconds of a change.
   *
   * <p>Because the first read has a side effect, {@link LocalRequestGuardFilter} requires the
   * client header on this GET as well. Since 3.5.2 the handler requires the header too, so a
   * cross-site GET cannot reach this method even if the filter's path matching ever diverges from
   * MVC routing again (a missing header here yields 400).
   */
  @GetMapping("/api/account/live")
  public LiveAccountService.LiveView live(
      @RequestHeader(LocalRequestGuardFilter.CLIENT_HEADER) String client) {
    return live.view();
  }

  /**
   * Account snapshot job. Only allowed inside the snapshot window (409 outside it); force=true only
   * works in DEV.
   */
  @PostMapping("/api/account/snapshot")
  public Map<String, Object> snapshot(@RequestParam(defaultValue = "false") boolean force) {
    return Map.of("jobId", facade.snapshot("MANUAL", force));
  }

  /** The latest snapshot, with the change against the previous snapshot. */
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

  /**
   * Maintains HOLDING in the pool from IBKR positions. Returns only the plan by default; apply=true
   * changes the pool.
   */
  @PostMapping("/api/account/holdings/sync")
  public HoldingSyncService.Result syncHoldings(@RequestParam(defaultValue = "false") boolean apply)
      throws Exception {
    return holdingSync.syncNow(apply, "MANUAL");
  }

  /**
   * Holding risk panel (3.5.0, ARCHITECTURE §29): for every position (and pool members not held),
   * the trend state, earnings warning, historical earnings reactions and stress scenario, and the
   * review conditions that fired. Read-only, database only. It checks only the rules the user set
   * and gives no buy/sell advice.
   */
  @GetMapping("/api/account/holdings-review")
  public HoldingReviewService.Review holdingsReview(@RequestParam(required = false) Integer days) {
    return review.review(days);
  }

  /**
   * Add-to-position check: evaluates "about to buy {@code shares} more" against the user's four
   * rules, read-only. 404 for an unknown symbol.
   */
  @GetMapping("/api/account/add-check/{symbol}")
  public HoldingReviewService.AddCheck addCheck(
      @PathVariable String symbol, @RequestParam double shares) {
    return review.addCheck(symbol, shares);
  }

  /**
   * Day-by-day trend state and review conditions (for historical validation,
   * scripts/research/holdings_review_report.py), read-only.
   */
  @GetMapping("/api/account/holdings-review/history/{symbol}")
  public List<HoldingReviewService.HistoryDay> holdingsReviewHistory(
      @PathVariable String symbol,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return review.history(symbol, from, to);
  }

  /** Account audit (post-close daily check): audits the latest closed trading day by default. */
  @GetMapping("/api/account/audit")
  public BarAuditService.Report audit(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate date) {
    return audit.audit(date);
  }
}
