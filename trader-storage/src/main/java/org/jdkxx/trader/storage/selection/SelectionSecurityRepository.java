package org.jdkxx.trader.storage.selection;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Security type (IBKR contract details {@code stockType}, §25.3 item 6), e.g. COMMON / ADR / REIT /
 * NY REG SHRS. IBKR is the authority here because Futu misclassifies some REITs as common stock.
 */
@Repository
@ConditionalOnProperty(name = "trader.storage.enabled", havingValue = "true")
public class SelectionSecurityRepository {

  private final JdbcTemplate jdbc;

  public SelectionSecurityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void save(long instrumentId, String securityType, Long conId) {
    jdbc.update(
        """
                INSERT INTO selection_security (instrument_id, security_type, ibkr_con_id) VALUES (?, ?, ?)
                ON CONFLICT (instrument_id) DO UPDATE SET security_type = EXCLUDED.security_type,
                    ibkr_con_id = EXCLUDED.ibkr_con_id, checked_at = now()""",
        instrumentId,
        securityType,
        conId);
  }

  /**
   * Instrument → security type; instruments whose type is NULL (not provided by the broker) are
   * left out, so absence means "cannot tell".
   */
  public Map<Long, String> types() {
    Map<Long, String> out = new HashMap<>();
    jdbc.query(
        "SELECT instrument_id, security_type FROM selection_security WHERE security_type IS NOT NULL",
        rs -> {
          out.put(rs.getLong(1), rs.getString(2));
        });
    return out;
  }
}
