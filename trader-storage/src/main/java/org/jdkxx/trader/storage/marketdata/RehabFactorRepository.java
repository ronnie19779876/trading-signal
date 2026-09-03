package org.jdkxx.trader.storage.marketdata;

import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.RehabFactor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.util.ArrayList;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class RehabFactorRepository {

    private final JdbcTemplate jdbc;

    public RehabFactorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 整体替换该标的的因子（券商会重算历史因子，不能只追加）。 */
    @Transactional
    public void replaceAll(long instrumentId, List<RehabFactor> factors) {
        jdbc.update("DELETE FROM rehab_factor WHERE instrument_id = ?", instrumentId);
        if (factors.isEmpty()) {
            return;
        }
        List<Object[]> args = new ArrayList<>(factors.size());
        for (RehabFactor f : factors) {
            args.add(new Object[] {instrumentId, Date.valueOf(f.exDate()), f.fwdA(), f.fwdB(), f.bwdA(), f.bwdB(),
                    f.companyActFlag(), f.dividend(), f.spDividend(), f.splitBase(), f.splitErt()});
        }
        jdbc.batchUpdate("""
                INSERT INTO rehab_factor (instrument_id, ex_date, fwd_a, fwd_b, bwd_a, bwd_b, company_act_flag, dividend,
                                          sp_dividend, split_base, split_ert)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (instrument_id, ex_date) DO UPDATE SET fwd_a = EXCLUDED.fwd_a, fwd_b = EXCLUDED.fwd_b,
                    bwd_a = EXCLUDED.bwd_a, bwd_b = EXCLUDED.bwd_b, company_act_flag = EXCLUDED.company_act_flag,
                    dividend = EXCLUDED.dividend, sp_dividend = EXCLUDED.sp_dividend, split_base = EXCLUDED.split_base,
                    split_ert = EXCLUDED.split_ert, fetched_at = now()""", args);
    }

    public List<RehabFactor> find(Instrument instrument, long instrumentId) {
        return jdbc.query("SELECT * FROM rehab_factor WHERE instrument_id = ? ORDER BY ex_date",
                (rs, i) -> new RehabFactor(instrument, rs.getDate("ex_date").toLocalDate(), rs.getBigDecimal("fwd_a"),
                        rs.getBigDecimal("fwd_b"), rs.getBigDecimal("bwd_a"), rs.getBigDecimal("bwd_b"),
                        rs.getLong("company_act_flag"), rs.getBigDecimal("dividend"), rs.getBigDecimal("sp_dividend"),
                        rs.getInt("split_base"), rs.getInt("split_ert")), instrumentId);
    }
}
