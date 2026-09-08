package org.jdkxx.trader.storage.marketdata;

import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.ValuationSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 估值快照。一只一天一行，重跑覆盖。
 * 亏损股的市盈率市净率为负是真实数据，查询与统计都不要按非负过滤。
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class ValuationRepository {

    /** 某一天的覆盖与合理性统计。 */
    public record DayStats(long rows, long suspended, long missingMarketCap, long negativePe) {
    }

    private final JdbcTemplate jdbc;

    public ValuationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等写入，返回条数。instrumentIds 给出每条快照对应的标的行 id。 */
    public int upsertAll(Map<Instrument, Long> instrumentIds, List<ValuationSnapshot> snapshots, LocalDate tradeDate) {
        List<Object[]> args = new ArrayList<>(snapshots.size());
        for (ValuationSnapshot v : snapshots) {
            Long id = instrumentIds.get(v.instrument());
            if (id == null) {
                continue;
            }
            args.add(new Object[] {id, Date.valueOf(tradeDate), Timestamp.from(v.asOf()), v.suspended(),
                    v.marketCap(), v.floatMarketCap(), v.issuedShares(), v.outstandingShares(),
                    v.pe(), v.peTtm(), v.pb(), v.eps(), v.netAssetPerShare(), v.netAsset(), v.netProfit(),
                    v.dividendTtm(), v.dividendYieldTtm(), v.turnoverRate(), v.navPerShare(), v.premium()});
        }
        if (args.isEmpty()) {
            return 0;
        }
        jdbc.batchUpdate("""
                INSERT INTO valuation_snapshot (instrument_id, trade_date, as_of, suspended, market_cap, float_market_cap,
                        issued_shares, outstanding_shares, pe, pe_ttm, pb, eps, net_asset_per_share, net_asset, net_profit,
                        dividend_ttm, dividend_yield_ttm, turnover_rate, nav_per_share, premium)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (instrument_id, trade_date) DO UPDATE SET
                    as_of = EXCLUDED.as_of, suspended = EXCLUDED.suspended, market_cap = EXCLUDED.market_cap,
                    float_market_cap = EXCLUDED.float_market_cap, issued_shares = EXCLUDED.issued_shares,
                    outstanding_shares = EXCLUDED.outstanding_shares, pe = EXCLUDED.pe, pe_ttm = EXCLUDED.pe_ttm,
                    pb = EXCLUDED.pb, eps = EXCLUDED.eps, net_asset_per_share = EXCLUDED.net_asset_per_share,
                    net_asset = EXCLUDED.net_asset, net_profit = EXCLUDED.net_profit, dividend_ttm = EXCLUDED.dividend_ttm,
                    dividend_yield_ttm = EXCLUDED.dividend_yield_ttm, turnover_rate = EXCLUDED.turnover_rate,
                    nav_per_share = EXCLUDED.nav_per_share, premium = EXCLUDED.premium, fetched_at = now()""", args);
        return args.size();
    }

    public List<ValuationSnapshot> find(Instrument instrument, long instrumentId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT as_of, suspended, market_cap, float_market_cap, issued_shares, outstanding_shares, pe, pe_ttm, pb,
                       eps, net_asset_per_share, net_asset, net_profit, dividend_ttm, dividend_yield_ttm, turnover_rate,
                       nav_per_share, premium
                FROM valuation_snapshot WHERE instrument_id = ? AND trade_date BETWEEN ? AND ? ORDER BY trade_date""",
                (rs, i) -> new ValuationSnapshot(instrument, rs.getTimestamp("as_of").toInstant(), rs.getBoolean("suspended"),
                        rs.getBigDecimal("market_cap"), rs.getBigDecimal("float_market_cap"),
                        (Long) rs.getObject("issued_shares"), (Long) rs.getObject("outstanding_shares"),
                        rs.getBigDecimal("pe"), rs.getBigDecimal("pe_ttm"), rs.getBigDecimal("pb"), rs.getBigDecimal("eps"),
                        rs.getBigDecimal("net_asset_per_share"), rs.getBigDecimal("net_asset"), rs.getBigDecimal("net_profit"),
                        rs.getBigDecimal("dividend_ttm"), rs.getBigDecimal("dividend_yield_ttm"),
                        rs.getBigDecimal("turnover_rate"), rs.getBigDecimal("nav_per_share"), rs.getBigDecimal("premium")),
                instrumentId, Date.valueOf(from), Date.valueOf(to));
    }

    public Optional<LocalDate> maxTradeDate() {
        Date d = jdbc.query("SELECT max(trade_date) FROM valuation_snapshot", rs -> rs.next() ? rs.getDate(1) : null);
        return Optional.ofNullable(d).map(Date::toLocalDate);
    }

    public Set<Long> instrumentIdsOn(LocalDate date) {
        return Set.copyOf(jdbc.queryForList("SELECT instrument_id FROM valuation_snapshot WHERE trade_date = ?",
                Long.class, Date.valueOf(date)));
    }

    /** 当天的合理性统计。市值为空只在非个股上正常，交由审计判断。 */
    public DayStats statsOn(LocalDate date) {
        return jdbc.query("""
                SELECT count(*) AS rows,
                       count(*) FILTER (WHERE suspended)              AS suspended,
                       count(*) FILTER (WHERE market_cap IS NULL)     AS missing_cap,
                       count(*) FILTER (WHERE pe < 0)                 AS negative_pe
                FROM valuation_snapshot WHERE trade_date = ?""",
                rs -> rs.next()
                        ? new DayStats(rs.getLong("rows"), rs.getLong("suspended"),
                                rs.getLong("missing_cap"), rs.getLong("negative_pe"))
                        : new DayStats(0, 0, 0, 0),
                Date.valueOf(date));
    }
}
