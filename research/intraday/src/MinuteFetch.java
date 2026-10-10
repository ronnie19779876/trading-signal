import com.futu.openapi.FTAPI;
import com.futu.openapi.FTAPI_Conn;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.FTSPI_Conn;
import com.futu.openapi.FTSPI_Qot;
import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotRequestHistoryKL;
import com.futu.openapi.pb.QotRequestHistoryKLQuota;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * H1 的分钟线下载（ARCHITECTURE §24.6）：只读，富途 {@code requestHistoryKL}，1 分钟、不复权、含盘前盘后（04:00 ~ 20:00）。
 *
 * <ul>
 *   <li>每条 H1 信号取「信号日 + 入场日」一段；同一只另按信号条数、固定种子抽同样多的非信号日对（对照组），一起取——
 *       同一标的 7 天内重复请求不再占额度（§24.2 实测），对照组不额外花额度；</li>
 *   <li>额度守卫：本研究最近 7 天新占不超过 {@code -Dbudget}（缺省 80，见 data/quota-ledger.csv），且券商剩余额度不得低于 20（留给生产）；</li>
 *   <li>每只一个缓存文件 data/minute/{symbol}.csv，写完才算完成；额度用完即停，下次重跑从没完成的那只接着取。</li>
 * </ul>
 * 连接参数沿用应用的环境变量 TRADER_FUTU_HOST / TRADER_FUTU_PORT，缺省读本机 config/secrets.yml（不入库）。
 */
public final class MinuteFetch implements FTSPI_Conn, FTSPI_Qot {

    static final int RESERVE = 20;
    static final long CONTROL_SEED = 20261010L;
    static final Path MINUTE = Lab.DATA.resolve("minute");
    static final Path LEDGER = Lab.DATA.resolve("quota-ledger.csv");

    private final Map<Integer, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();
    private final CompletableFuture<Long> ready = new CompletableFuture<>();
    private FTAPI_Conn_Qot qot;

    @Override
    public void onInitConnect(FTAPI_Conn c, long err, String desc) {
        ready.complete(err);
    }

    @Override
    public void onDisconnect(FTAPI_Conn c, long err) {
        System.out.println("富途连接断开 " + err);
        pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException("断开")));
    }

    @Override
    public void onReply_RequestHistoryKL(FTAPI_Conn c, int sn, QotRequestHistoryKL.Response r) {
        done(sn, r);
    }

    @Override
    public void onReply_RequestHistoryKLQuota(FTAPI_Conn c, int sn, QotRequestHistoryKLQuota.Response r) {
        done(sn, r);
    }

    private void done(int sn, Object r) {
        CompletableFuture<Object> f = pending.remove(sn);
        if (f != null) {
            f.complete(r);
        }
    }

    @SuppressWarnings("unchecked")
    private synchronized <T> T call(IntSupplier send) throws Exception {
        CompletableFuture<Object> f = new CompletableFuture<>();
        int sn = send.getAsInt();
        if (sn == 0) {
            throw new IllegalStateException("富途请求发送失败");
        }
        pending.put(sn, f);
        return (T) f.get(60, TimeUnit.SECONDS);
    }

    public static void main(String[] args) throws Exception {
        int budget = Integer.getInteger("budget", 80);
        new MinuteFetch().run(budget);
    }

    private void run(int budget) throws Exception {
        Map<String, TreeSet<LocalDate[]>> windows = windows();
        Files.createDirectories(MINUTE);
        // 「每周最多 budget 只」：只数最近 7 天新占的（富途额度也是 7 天滚动）
        Set<String> ledger = new HashSet<>();
        Instant weekAgo = Instant.now().minus(java.time.Duration.ofDays(7));
        if (Files.exists(LEDGER)) {
            Files.readAllLines(LEDGER).stream().skip(1).map(l -> l.split(","))
                    .filter(f -> Instant.parse(f[1]).isAfter(weekAgo)).forEach(f -> ledger.add(f[0]));
        } else {
            Files.writeString(LEDGER, "symbol,requestedAt\n");
        }

        FTAPI.init();
        qot = new FTAPI_Conn_Qot();
        qot.setClientInfo("trading-signal-research", 1);
        qot.setConnSpi(this);
        qot.setQotSpi(this);
        String[] hp = futuEndpoint();
        qot.initConnect(hp[0], Integer.parseInt(hp[1]), false);
        if (ready.get(20, TimeUnit.SECONDS) != 0) {
            throw new IllegalStateException("富途连接失败");
        }
        int fetched = 0;
        int skipped = 0;
        try {
            for (var e : windows.entrySet()) {
                String symbol = e.getKey();
                Path file = MINUTE.resolve(symbol + ".csv");
                if (Files.exists(file)) {
                    skipped++;
                    continue;
                }
                Quota q = quota();
                boolean free = q.symbols().contains(symbol);
                if (!free) {
                    if (ledger.size() >= budget) {
                        System.out.printf("本研究最近 7 天已新占 %d 只，达到每周上限 %d，停。%n", ledger.size(), budget);
                        break;
                    }
                    if (q.remain() <= RESERVE) {
                        System.out.printf("券商剩余额度 %d，不低于 %d 的预留线，停。%n", q.remain(), RESERVE);
                        break;
                    }
                    Files.writeString(LEDGER, symbol + "," + Instant.now() + "\n", StandardOpenOption.APPEND);
                    ledger.add(symbol);
                }
                StringBuilder sb = new StringBuilder("time,open,high,low,close,volume,turnover\n");
                int bars = 0;
                for (LocalDate[] w : e.getValue()) {
                    for (QotCommon.KLine k : history(symbol, w[0], w[1])) {
                        sb.append(k.getTime()).append(',').append(k.getOpenPrice()).append(',').append(k.getHighPrice()).append(',')
                                .append(k.getLowPrice()).append(',').append(k.getClosePrice()).append(',').append(k.getVolume()).append(',')
                                .append(k.getTurnover()).append('\n');
                        bars++;
                    }
                }
                Path tmp = MINUTE.resolve(symbol + ".csv.tmp");
                Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
                Files.move(tmp, file);
                fetched++;
                System.out.printf("%s：%d 段 %d 根%s；本研究已新占 %d 只，券商剩余 %d%n", symbol, e.getValue().size(), bars,
                        free ? "（额度内已有，不占）" : "", ledger.size(), free ? q.remain() : q.remain() - 1);
            }
        } finally {
            qot.close();
            FTAPI.unInit();
        }
        System.out.printf("本次取 %d 只，已有缓存 %d 只，共需 %d 只%n", fetched, skipped, windows.size());
    }

    /** 每只要取的日期段：H1 信号的（信号日, 入场日），加同样多的随机非信号日对（固定种子）。 */
    static Map<String, TreeSet<LocalDate[]>> windows() throws Exception {
        List<Map<String, String>> signals = Lab.readCsv(Lab.DATA.resolve("signals.csv"));
        Map<String, TreeSet<LocalDate[]>> out = new TreeMap<>();
        Map<String, Set<LocalDate>> signalDays = new TreeMap<>();
        for (Map<String, String> s : signals) {
            if (!"true".equals(s.get("h1"))) {
                continue;
            }
            out.computeIfAbsent(s.get("symbol"), k -> new TreeSet<>((a, b) -> a[0].compareTo(b[0])))
                    .add(new LocalDate[]{LocalDate.parse(s.get("signalDate")), LocalDate.parse(s.get("entryDate"))});
            signalDays.computeIfAbsent(s.get("symbol"), k -> new HashSet<>()).add(LocalDate.parse(s.get("signalDate")));
        }
        List<LocalDate> days;
        try (DevDb db = new DevDb()) {
            days = db.tradingDays(FreezeSignals.WINDOW_FROM, FreezeSignals.WINDOW_TO);
        }
        SplittableRandom rnd = new SplittableRandom(CONTROL_SEED);
        for (var e : out.entrySet()) {
            Set<LocalDate> taken = signalDays.get(e.getKey());
            int want = taken.size();
            List<LocalDate[]> controls = new ArrayList<>();
            while (controls.size() < want) {
                int i = rnd.nextInt(days.size() - 1);
                LocalDate d = days.get(i);
                if (!taken.contains(d)) {
                    taken.add(d);
                    controls.add(new LocalDate[]{d, days.get(i + 1)});
                }
            }
            e.getValue().addAll(controls);
        }
        return out;
    }

    /** 一段 [from 00:00:00, to 23:59:59]（必须写到秒，只写日期返回 0 根且照样占额度，§24.2），自动翻页。 */
    private List<QotCommon.KLine> history(String symbol, LocalDate from, LocalDate to) throws Exception {
        List<QotCommon.KLine> out = new ArrayList<>();
        org.jdkxx.trader.shaded.futu.protobuf.ByteString key = null;
        do {
            QotRequestHistoryKL.C2S.Builder c2s = QotRequestHistoryKL.C2S.newBuilder()
                    .setRehabType(QotCommon.RehabType.RehabType_None_VALUE)
                    .setKlType(QotCommon.KLType.KLType_1Min_VALUE)
                    .setSecurity(QotCommon.Security.newBuilder().setMarket(QotCommon.QotMarket.QotMarket_US_Security_VALUE).setCode(symbol))
                    .setBeginTime(from + " 00:00:00").setEndTime(to + " 23:59:59")
                    .setMaxAckKLNum(1000).setExtendedTime(true);
            if (key != null) {
                c2s.setNextReqKey(key);
            }
            QotRequestHistoryKL.Request req = QotRequestHistoryKL.Request.newBuilder().setC2S(c2s).build();
            Thread.sleep(600);   // 限频 60 / 30s
            QotRequestHistoryKL.Response r = call(() -> qot.requestHistoryKL(req));
            if (r.getRetType() != 0) {
                throw new IllegalStateException(symbol + " " + from + " 取分钟线失败：" + r.getRetMsg());
            }
            out.addAll(r.getS2C().getKlListList());
            key = r.getS2C().hasNextReqKey() && !r.getS2C().getNextReqKey().isEmpty() && r.getS2C().getKlListCount() > 0
                    ? r.getS2C().getNextReqKey() : null;
        } while (key != null);
        return out;
    }

    record Quota(int used, int remain, Set<String> symbols) {
    }

    private Quota quota() throws Exception {
        Thread.sleep(3100);   // 限频 10 / 30s
        QotRequestHistoryKLQuota.Response r = call(() -> qot.requestHistoryKLQuota(QotRequestHistoryKLQuota.Request.newBuilder()
                .setC2S(QotRequestHistoryKLQuota.C2S.newBuilder().setBGetDetail(true)).build()));
        Set<String> symbols = new HashSet<>();
        r.getS2C().getDetailListList().forEach(d -> symbols.add(d.getSecurity().getCode()));
        return new Quota(r.getS2C().getUsedQuota(), r.getS2C().getRemainQuota(), symbols);
    }

    private static String[] futuEndpoint() throws Exception {
        String host = System.getenv("TRADER_FUTU_HOST");
        String port = System.getenv("TRADER_FUTU_PORT");
        if (host != null && port != null) {
            return new String[]{host, port};
        }
        // 本机 config/secrets.yml 的 trader.futu 段（不入库）
        Path secrets = Path.of("../../config/secrets.yml");
        boolean inFutu = false;
        for (String line : Files.readAllLines(secrets)) {
            String t = line.trim();
            if (t.equals("futu:")) {
                inFutu = true;
            } else if (inFutu && !line.startsWith("    ")) {
                inFutu = false;
            } else if (inFutu && t.startsWith("host:")) {
                host = t.substring(5).trim();
            } else if (inFutu && t.startsWith("port:")) {
                port = t.substring(5).trim();
            }
        }
        if (host == null || port == null) {
            throw new IllegalStateException("没有 TRADER_FUTU_HOST / TRADER_FUTU_PORT，config/secrets.yml 里也没有 trader.futu.host / port");
        }
        return new String[]{host, port};
    }
}
