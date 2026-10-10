import org.jdkxx.trader.domain.DailyBar;
import org.jdkxx.trader.domain.Instrument;
import org.jdkxx.trader.domain.Market;
import org.jdkxx.trader.domain.RehabFactor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 只读访问开发库 db_trader_dev。行映射与 trader-storage 的仓库逐字段一致（DailyBarRepository / RehabFactorRepository），
 * 研究代码据此调用领域层的同一套纯函数。连接参数沿用应用的环境变量 TRADER_DB_HOST / TRADER_DB_PORT / TRADER_DB_PASSWORD，
 * 口令缺省时按 libpq 规则从 ~/.pgpass 取（回环地址互相等价）。
 */
final class DevDb implements AutoCloseable {

    private static final String DB = "db_trader_dev";
    private static final String USER = "trader";
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "::1");

    private final Connection conn;

    DevDb() throws Exception {
        String host = env("TRADER_DB_HOST", "127.0.0.1");
        String port = env("TRADER_DB_PORT", "5432");
        String credential = System.getenv("TRADER_DB_PASSWORD");
        if (credential == null || credential.isEmpty()) {
            credential = pgpass(host, port);
        }
        conn = DriverManager.getConnection("jdbc:postgresql://" + host + ":" + port + "/" + DB, USER, credential);
        conn.setReadOnly(true);
        try (ResultSet rs = conn.createStatement().executeQuery("SELECT name FROM app_environment")) {
            if (!rs.next() || !"DEV".equalsIgnoreCase(rs.getString(1))) {
                throw new IllegalStateException("连到的不是开发库（app_environment 不是 DEV），拒绝继续");
            }
        }
    }

    long instrumentId(String symbol) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM instrument WHERE market = 'US' AND symbol = ?")) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("开发库没有标的 " + symbol);
                }
                return rs.getLong(1);
            }
        }
    }

    List<DailyBar> bars(Instrument instrument, long id, LocalDate from, LocalDate to) throws SQLException {
        List<DailyBar> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM daily_bar WHERE instrument_id = ? AND trade_date BETWEEN ? AND ? ORDER BY trade_date")) {
            ps.setLong(1, id);
            ps.setDate(2, Date.valueOf(from));
            ps.setDate(3, Date.valueOf(to));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DailyBar(instrument, rs.getDate("trade_date").toLocalDate(), rs.getBigDecimal("open"),
                            rs.getBigDecimal("high"), rs.getBigDecimal("low"), rs.getBigDecimal("close"), rs.getBigDecimal("last_close"),
                            rs.getLong("volume"), rs.getBigDecimal("turnover"), rs.getBigDecimal("turnover_rate"),
                            rs.getBigDecimal("change_rate"), rs.getBigDecimal("pe"), rs.getBoolean("blank")));
                }
            }
        }
        return out;
    }

    List<RehabFactor> factors(Instrument instrument, long id) throws SQLException {
        List<RehabFactor> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM rehab_factor WHERE instrument_id = ? ORDER BY ex_date")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new RehabFactor(instrument, rs.getDate("ex_date").toLocalDate(), rs.getBigDecimal("fwd_a"),
                            rs.getBigDecimal("fwd_b"), rs.getBigDecimal("bwd_a"), rs.getBigDecimal("bwd_b"),
                            rs.getLong("company_act_flag"), rs.getBigDecimal("dividend"), rs.getBigDecimal("sp_dividend"),
                            rs.getInt("split_base"), rs.getInt("split_ert"), rs.getInt("join_base"), rs.getInt("join_ert"),
                            rs.getInt("bonus_base"), rs.getInt("bonus_ert"), rs.getInt("transfer_base"), rs.getInt("transfer_ert")));
                }
            }
        }
        return out;
    }

    List<LocalDate> tradingDays(LocalDate from, LocalDate to) throws SQLException {
        List<LocalDate> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT trade_date FROM trading_day WHERE market = ? AND trade_date BETWEEN ? AND ? ORDER BY trade_date")) {
            ps.setString(1, Market.US.name());
            ps.setDate(2, Date.valueOf(from));
            ps.setDate(3, Date.valueOf(to));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getDate(1).toLocalDate());
                }
            }
        }
        return out;
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    /** libpq 规则：逐行 host:port:db:user:password，* 通配，取第一条匹配；回环地址互相等价。 */
    private static String pgpass(String host, String port) throws Exception {
        Path file = Path.of(System.getProperty("user.home"), ".pgpass");
        if (!Files.isReadable(file)) {
            throw new IllegalStateException("没有 TRADER_DB_PASSWORD，也读不到 ~/.pgpass");
        }
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split(":", 5);
            if (f.length == 5 && hostMatches(f[0], host) && match(f[1], port) && match(f[2], DB) && match(f[3], USER)) {
                return f[4];
            }
        }
        throw new IllegalStateException("~/.pgpass 里没有 " + DB + " 的条目");
    }

    private static boolean hostMatches(String pattern, String host) {
        return match(pattern, host) || (LOOPBACK.contains(pattern) && LOOPBACK.contains(host));
    }

    private static boolean match(String pattern, String value) {
        return "*".equals(pattern) || pattern.equals(value);
    }
}
