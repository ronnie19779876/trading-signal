import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 研究程序共用的小工具：只读访问开发实例、CSV 读写、校验和。 */
final class Lab {

    static final Path DATA = Path.of("data");
    static final String DEV = System.getProperty("dev", "http://127.0.0.1:8083");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private Lab() {
    }

    /** 只发 GET：研究程序对开发实例只读。 */
    static JsonNode get(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(DEV + path)).timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<String> rsp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (rsp.statusCode() != 200) {
            throw new IOException("GET " + path + " → " + rsp.statusCode() + " " + rsp.body());
        }
        return JSON.readTree(rsp.body());
    }

    static void writeCsv(Path file, List<String> header, List<List<?>> rows) throws IOException {
        StringBuilder sb = new StringBuilder(String.join(",", header)).append('\n');
        for (List<?> r : rows) {
            for (int i = 0; i < r.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                Object v = r.get(i);
                sb.append(v == null ? "" : v.toString());
            }
            sb.append('\n');
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /** 每行一个按表头取值的 Map；空串为 null。 */
    static List<Map<String, String>> readCsv(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        String[] head = lines.get(0).split(",", -1);
        List<Map<String, String>> out = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            if (line.isEmpty()) {
                continue;
            }
            String[] v = line.split(",", -1);
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < head.length; i++) {
                m.put(head[i], v[i].isEmpty() ? null : v[i]);
            }
            out.add(m);
        }
        return out;
    }

    static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
