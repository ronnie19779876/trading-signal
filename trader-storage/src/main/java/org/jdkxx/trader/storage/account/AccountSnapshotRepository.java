package org.jdkxx.trader.storage.account;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class AccountSnapshotRepository {

    /** 一个对账项。 */
    public record ReconCheck(String name, String status, String detail) {
    }

    private static final RowMapper<AccountSnapshotRow> SNAPSHOT = (rs, i) -> new AccountSnapshotRow(
            rs.getLong("id"), rs.getString("broker"), rs.getString("account_key"), rs.getString("account_mask"),
            rs.getDate("as_of_date").toLocalDate(), rs.getTimestamp("taken_at").toInstant(), rs.getString("currency"),
            rs.getBigDecimal("net_liquidation"), rs.getBigDecimal("total_cash"), rs.getBigDecimal("stock_market_value"),
            rs.getBigDecimal("gross_position_value"), rs.getBigDecimal("available_funds"), rs.getBigDecimal("buying_power"),
            rs.getBigDecimal("excess_liquidity"), rs.getBigDecimal("unrealized_pnl"), rs.getBigDecimal("realized_pnl"),
            rs.getBigDecimal("accrued_dividend"), rs.getBigDecimal("position_value"), rs.getInt("positions"),
            rs.getString("recon_status"), rs.getString("recon"),
            rs.getObject("job_run_id") == null ? null : rs.getLong("job_run_id"));

    private static final RowMapper<PositionSnapshotRow> POSITION = (rs, i) -> new PositionSnapshotRow(
            rs.getString("broker_ref"), rs.getString("symbol"),
            rs.getObject("instrument_id") == null ? null : rs.getLong("instrument_id"),
            rs.getString("security_type"), rs.getString("currency"), rs.getString("exchange"),
            rs.getBigDecimal("quantity"), rs.getBigDecimal("average_cost"), rs.getBigDecimal("price"),
            rs.getString("price_source"), rs.getBigDecimal("market_value"), rs.getBigDecimal("cost_basis"),
            rs.getBigDecimal("unrealized_pnl"), rs.getBoolean("cash_equivalent"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public AccountSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 保存一份快照（同一账户同一天覆盖，持仓整体替换），返回快照 id。
     * header 的 id 与 recon 字段忽略：recon 由 checks 序列化。
     */
    @Transactional
    public long save(AccountSnapshotRow header, Map<String, String> raw, List<ReconCheck> checks, List<PositionSnapshotRow> positions) {
        Long id = jdbc.queryForObject("""
                INSERT INTO account_snapshot (broker, account_key, account_mask, as_of_date, taken_at, currency, net_liquidation,
                        total_cash, stock_market_value, gross_position_value, available_funds, buying_power, excess_liquidity,
                        unrealized_pnl, realized_pnl, accrued_dividend, position_value, positions, recon_status, recon, raw, job_run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                ON CONFLICT (broker, account_key, as_of_date) DO UPDATE SET
                    account_mask = EXCLUDED.account_mask, taken_at = EXCLUDED.taken_at, currency = EXCLUDED.currency,
                    net_liquidation = EXCLUDED.net_liquidation, total_cash = EXCLUDED.total_cash,
                    stock_market_value = EXCLUDED.stock_market_value, gross_position_value = EXCLUDED.gross_position_value,
                    available_funds = EXCLUDED.available_funds, buying_power = EXCLUDED.buying_power,
                    excess_liquidity = EXCLUDED.excess_liquidity, unrealized_pnl = EXCLUDED.unrealized_pnl,
                    realized_pnl = EXCLUDED.realized_pnl, accrued_dividend = EXCLUDED.accrued_dividend,
                    position_value = EXCLUDED.position_value, positions = EXCLUDED.positions,
                    recon_status = EXCLUDED.recon_status, recon = EXCLUDED.recon, raw = EXCLUDED.raw, job_run_id = EXCLUDED.job_run_id
                RETURNING id""", Long.class,
                header.broker(), header.accountKey(), header.accountMask(), Date.valueOf(header.asOfDate()),
                Timestamp.from(header.takenAt()), header.currency(), header.netLiquidation(), header.totalCash(),
                header.stockMarketValue(), header.grossPositionValue(), header.availableFunds(), header.buyingPower(),
                header.excessLiquidity(), header.unrealizedPnl(), header.realizedPnl(), header.accruedDividend(),
                header.positionValue(), header.positions(), header.reconStatus(), toJson(checks), toJson(raw), header.jobRunId());
        jdbc.update("DELETE FROM position_snapshot WHERE snapshot_id = ?", id);
        if (!positions.isEmpty()) {
            List<Object[]> args = new ArrayList<>(positions.size());
            for (PositionSnapshotRow p : positions) {
                args.add(new Object[] {id, p.brokerRef(), p.symbol(), p.instrumentId(), p.securityType(), p.currency(), p.exchange(),
                        p.quantity(), p.averageCost(), p.price(), p.priceSource(), p.marketValue(), p.costBasis(),
                        p.unrealizedPnl(), p.cashEquivalent()});
            }
            jdbc.batchUpdate("""
                    INSERT INTO position_snapshot (snapshot_id, broker_ref, symbol, instrument_id, security_type, currency, exchange,
                            quantity, average_cost, price, price_source, market_value, cost_basis, unrealized_pnl, cash_equivalent)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", args);
        }
        return id;
    }

    public Optional<AccountSnapshotRow> latest() {
        return jdbc.query("SELECT * FROM account_snapshot ORDER BY as_of_date DESC, taken_at DESC LIMIT 1", SNAPSHOT)
                .stream().findFirst();
    }

    public List<AccountSnapshotRow> between(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT * FROM account_snapshot WHERE as_of_date BETWEEN ? AND ? ORDER BY as_of_date, account_key",
                SNAPSHOT, Date.valueOf(from), Date.valueOf(to));
    }

    public List<PositionSnapshotRow> positions(long snapshotId) {
        return jdbc.query("SELECT * FROM position_snapshot WHERE snapshot_id = ? ORDER BY market_value DESC NULLS LAST, symbol",
                POSITION, snapshotId);
    }

    public boolean existsOn(LocalDate date) {
        Boolean b = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM account_snapshot WHERE as_of_date = ?)", Boolean.class,
                Date.valueOf(date));
        return Boolean.TRUE.equals(b);
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化账户快照失败", e);
        }
    }
}
