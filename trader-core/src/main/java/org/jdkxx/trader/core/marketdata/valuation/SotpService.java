package org.jdkxx.trader.core.marketdata.valuation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.domain.valuation.SotpAssumptions;
import org.jdkxx.trader.domain.valuation.SotpResult;
import org.jdkxx.trader.domain.valuation.SotpScenario;
import org.jdkxx.trader.domain.valuation.SotpSegment;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.valuation.SotpModelRepository;
import org.jdkxx.trader.storage.valuation.SotpModelRow;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 分部估值的存取与试算。计算全在 {@link SotpCalculator}（纯函数），这里只负责取底座、落库、拼装。
 *
 * <p>接收的是<b>平铺的请求对象</b>而不是直接反序列化领域对象：领域记录在构造时就校验
 * （三情景缺一不可、口径备注必填、净利率只收小数），让 Jackson 去撞这些校验，报错会被包成
 * 反序列化异常、丢掉中文原因；在这里显式构造，非法输入才能原样变成 400 PARAM_INVALID。
 */
public class SotpService {

    private final InstrumentDirectory directory;
    private final SotpBasisService basis;
    private final SotpModelRepository models;
    private final ObjectMapper json;

    public SotpService(InstrumentDirectory directory, SotpBasisService basis, SotpModelRepository models, ObjectMapper json) {
        this.directory = directory;
        this.basis = basis;
        this.models = models;
        this.json = json;
    }

    /** 一条业务线的请求形态：净利率与要求回报率都收<b>小数</b>。 */
    public record SegmentRequest(String name, String scopeNote, Map<String, CaseRequest> cases) {
    }

    public record CaseRequest(Double volume, Double price, Double netMargin, Double pe) {
    }

    /**
     * 保存 / 试算的请求。
     *
     * @param name 保存时必填，同一标的下唯一；试算可为空
     */
    public record ModelRequest(String name, LocalDate asOf, Integer targetYear, Double discountRate,
                               Double targetShares, Double targetNetCash, String note, List<SegmentRequest> segments) {
    }

    /** 一套已存的假设连同算出来的结果。 */
    /**
     * @param asOf     存下来的估值基准日（用户当初填的假设，原样回显）
     * @param valuedAt 本次<b>实际</b>折算到的日期 = 现价所属交易日与 asOf 中较晚的那个。
     *     两者不同就说明方案存了一段时间了：折现期从旧基准日算起、涨跌幅却拿今天的收盘比，
     *     两个日期对不上，方案存得越久偏差越大（2026-09-25 全项目审查发现）。
     *     折算改到现价所属日，比较的两边就属于同一天；asOf 仍然原样留着，不改用户存的东西。
     */
    public record SavedModel(long id, String symbol, String name, LocalDate asOf, LocalDate valuedAt, int targetYear,
                             double discountRate, double targetShares, double targetNetCash, String note,
                             List<SegmentRequest> segments, SotpBasisService.Basis basis, SotpResult result) {
    }

    public SotpBasisService.Basis inputs(String symbol) {
        return basis.of(symbol);
    }

    public List<SavedModel> models(String symbol) {
        InstrumentRow row = directory.require(symbol);
        SotpBasisService.Basis b = basis.of(row.symbol());
        List<SavedModel> out = new ArrayList<>();
        for (SotpModelRow r : models.findByInstrument(row.id())) {
            out.add(toSaved(r, b));
        }
        return List.copyOf(out);
    }

    /** 试算，不落库。 */
    public SotpResult calc(String symbol, ModelRequest request) {
        SotpBasisService.Basis b = basis.of(directory.require(symbol).symbol());
        return SotpCalculator.calculate(assumptions(request), b.forCalculator());
    }

    public SavedModel save(String symbol, ModelRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("缺方案名称：同一标的下可以存多套，靠名称区分");
        }
        InstrumentRow row = directory.require(symbol);
        SotpAssumptions a = assumptions(request);          // 先校验再落库，别把非法假设写进去
        String segments = write(request.segments());
        long id = models.upsert(new SotpModelRow(null, row.id(), row.symbol(), request.name().trim(), a.asOf(),
                a.targetYear(), BigDecimal.valueOf(a.discountRate()), BigDecimal.valueOf(a.targetShares()),
                BigDecimal.valueOf(a.targetNetCash()), segments, request.note(), null, null));
        return models.findById(id).map(r -> toSaved(r, basis.of(row.symbol())))
                .orElseThrow(() -> new IllegalStateException("刚写入的方案读不回来：id " + id));
    }

    public void delete(long id) {
        if (models.delete(id) == 0) {
            throw new NoSuchElementException("没有这套方案：id " + id);
        }
    }

    private SavedModel toSaved(SotpModelRow r, SotpBasisService.Basis b) {
        List<SegmentRequest> segments = read(r.segments());
        LocalDate valuedAt = valuationDate(r.asOf(), b.priceDate(), r.targetYear());
        ModelRequest request = new ModelRequest(r.name(), valuedAt, r.targetYear(), r.discountRate().doubleValue(),
                r.targetShares().doubleValue(), r.targetNetCash().doubleValue(), r.note(), segments);
        SotpResult result = SotpCalculator.calculate(assumptions(request), b.forCalculator());
        return new SavedModel(r.id(), r.symbol(), r.name(), r.asOf(), valuedAt, r.targetYear(), r.discountRate().doubleValue(),
                r.targetShares().doubleValue(), r.targetNetCash().doubleValue(), r.note(), segments, b, result);
    }

    /**
     * 实际折算到哪一天：取现价所属交易日与存下来的基准日中<b>较晚</b>的那个。
     * 现值与它要比较的现价必须属于同一天，否则方案存得越久偏差越大。
     * 现价日已经跨过目标年时退回 asOf——{@link SotpAssumptions} 不接受目标年早于基准日所在年。
     */
    static LocalDate valuationDate(LocalDate asOf, LocalDate priceDate, int targetYear) {
        if (priceDate == null || !priceDate.isAfter(asOf) || priceDate.getYear() > targetYear) {
            return asOf;
        }
        return priceDate;
    }

    private SotpAssumptions assumptions(ModelRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("缺假设");
        }
        require(request.asOf(), "估值基准日");
        require(request.targetYear(), "目标年");
        require(request.discountRate(), "要求回报率");
        require(request.targetShares(), "目标年股数");
        require(request.targetNetCash(), "目标年净现金");
        if (request.segments() == null || request.segments().isEmpty()) {
            throw new IllegalArgumentException("至少要有一条业务线");
        }
        List<SotpSegment> segments = new ArrayList<>();
        for (SegmentRequest s : request.segments()) {
            segments.add(segment(s));
        }
        return new SotpAssumptions(request.asOf(), request.targetYear(), request.discountRate(),
                request.targetShares(), request.targetNetCash(), segments);
    }

    private SotpSegment segment(SegmentRequest s) {
        if (s == null) {
            throw new IllegalArgumentException("业务线不能为空");
        }
        Map<SotpScenario, SotpSegment.SegmentCase> cases = new EnumMap<>(SotpScenario.class);
        if (s.cases() != null) {
            for (Map.Entry<String, CaseRequest> e : s.cases().entrySet()) {
                SotpScenario scenario = scenario(e.getKey(), s.name());
                CaseRequest c = e.getValue();
                if (c == null || c.volume() == null || c.price() == null || c.netMargin() == null || c.pe() == null) {
                    throw new IllegalArgumentException("业务线「" + s.name() + "」的 " + scenario + " 情景缺量、价、净利率或本益比");
                }
                cases.put(scenario, new SotpSegment.SegmentCase(c.volume(), c.price(), c.netMargin(), c.pe()));
            }
        }
        return new SotpSegment(s.name(), s.scopeNote(), cases);
    }

    private SotpScenario scenario(String key, String segmentName) {
        try {
            return SotpScenario.valueOf(key.trim().toUpperCase());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("业务线「" + segmentName + "」的情景名非法：" + key + "（只接受 BEAR / BASE / BULL）");
        }
    }

    private void require(Object v, String what) {
        if (v == null) {
            throw new IllegalArgumentException("缺" + what);
        }
    }

    private String write(List<SegmentRequest> segments) {
        try {
            return json.writeValueAsString(segments);
        } catch (Exception e) {
            throw new IllegalStateException("业务线假设序列化失败：" + e, e);
        }
    }

    private List<SegmentRequest> read(String raw) {
        try {
            return json.readValue(raw, new TypeReference<List<SegmentRequest>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("业务线假设反序列化失败：" + e, e);
        }
    }
}
