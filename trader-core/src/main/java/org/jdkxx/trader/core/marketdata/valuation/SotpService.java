package org.jdkxx.trader.core.marketdata.valuation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.jdkxx.trader.core.marketdata.InstrumentDirectory;
import org.jdkxx.trader.domain.valuation.SotpAssumptions;
import org.jdkxx.trader.domain.valuation.SotpResult;
import org.jdkxx.trader.domain.valuation.SotpScenario;
import org.jdkxx.trader.domain.valuation.SotpSegment;
import org.jdkxx.trader.storage.marketdata.InstrumentRow;
import org.jdkxx.trader.storage.valuation.SotpModelRepository;
import org.jdkxx.trader.storage.valuation.SotpModelRow;

/**
 * Storage and trial calculation of sum-of-the-parts models. All arithmetic lives in {@link
 * SotpCalculator} (pure functions); this class only fetches the basis, persists and assembles.
 *
 * <p>It accepts <b>flat request objects</b> instead of deserializing the domain objects directly:
 * the domain records validate in their constructors (all three scenarios required, scope note
 * required, net margin only as a fraction). Letting Jackson run into those checks would wrap the
 * error in a deserialization exception and lose the Chinese reason text; constructing them
 * explicitly here turns invalid input into a 400 PARAM_INVALID with the message intact.
 */
public class SotpService {

  private final InstrumentDirectory directory;
  private final SotpBasisService basis;
  private final SotpModelRepository models;
  private final ObjectMapper json;

  public SotpService(
      InstrumentDirectory directory,
      SotpBasisService basis,
      SotpModelRepository models,
      ObjectMapper json) {
    this.directory = directory;
    this.basis = basis;
    this.models = models;
    this.json = json;
  }

  /**
   * Request shape of one business line: net margin and required return are both <b>fractions</b>.
   */
  public record SegmentRequest(String name, String scopeNote, Map<String, CaseRequest> cases) {}

  public record CaseRequest(Double volume, Double price, Double netMargin, Double pe) {}

  /**
   * Request to save / trial-calculate.
   *
   * @param name required when saving, unique per instrument; may be empty for a trial calculation
   */
  public record ModelRequest(
      String name,
      LocalDate asOf,
      Integer targetYear,
      Double discountRate,
      Double targetShares,
      Double targetNetCash,
      String note,
      List<SegmentRequest> segments) {}

  /**
   * A saved set of assumptions together with the computed result.
   *
   * @param asOf the stored valuation date (the assumption the user entered, echoed back unchanged)
   * @param valuedAt the date this result was <b>actually</b> discounted to = the later of the
   *     current price's trading day and asOf. When the two differ, the model has been stored for a
   *     while: the discount period would start from the old valuation date while the upside
   *     compares against today's close, so the two dates disagree and the error grows the longer
   *     the model is kept (found in the 2026-09-25 full-project review). Discounting to the price's
   *     day puts both sides of the comparison on the same day; asOf is kept as is, the user's
   *     stored input is not changed.
   */
  public record SavedModel(
      long id,
      String symbol,
      String name,
      LocalDate asOf,
      LocalDate valuedAt,
      int targetYear,
      double discountRate,
      double targetShares,
      double targetNetCash,
      String note,
      List<SegmentRequest> segments,
      SotpBasisService.Basis basis,
      SotpResult result) {}

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

  /** Trial calculation; nothing is persisted. */
  public SotpResult calc(String symbol, ModelRequest request) {
    SotpBasisService.Basis b = basis.of(directory.require(symbol).symbol());
    return SotpCalculator.calculate(assumptions(request), b.forCalculator());
  }

  public SavedModel save(String symbol, ModelRequest request) {
    if (request == null || request.name() == null || request.name().isBlank()) {
      throw new IllegalArgumentException("缺方案名称：同一标的下可以存多套，靠名称区分");
    }
    InstrumentRow row = directory.require(symbol);
    SotpAssumptions a =
        assumptions(request); // validate before persisting; never store invalid assumptions
    String segments = write(request.segments());
    long id =
        models.upsert(
            new SotpModelRow(
                null,
                row.id(),
                row.symbol(),
                request.name().trim(),
                a.asOf(),
                a.targetYear(),
                BigDecimal.valueOf(a.discountRate()),
                BigDecimal.valueOf(a.targetShares()),
                BigDecimal.valueOf(a.targetNetCash()),
                segments,
                request.note(),
                null,
                null));
    return models
        .findById(id)
        .map(r -> toSaved(r, basis.of(row.symbol())))
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
    ModelRequest request =
        new ModelRequest(
            r.name(),
            valuedAt,
            r.targetYear(),
            r.discountRate().doubleValue(),
            r.targetShares().doubleValue(),
            r.targetNetCash().doubleValue(),
            r.note(),
            segments);
    SotpResult result = SotpCalculator.calculate(assumptions(request), b.forCalculator());
    return new SavedModel(
        r.id(),
        r.symbol(),
        r.name(),
        r.asOf(),
        valuedAt,
        r.targetYear(),
        r.discountRate().doubleValue(),
        r.targetShares().doubleValue(),
        r.targetNetCash().doubleValue(),
        r.note(),
        segments,
        b,
        result);
  }

  /**
   * Which day to actually discount to: the <b>later</b> of the current price's trading day and the
   * stored valuation date. The present value and the price it is compared with must belong to the
   * same day, otherwise the error grows the longer the model is kept. When the price date has
   * already passed the target year, fall back to asOf: {@link SotpAssumptions} rejects a target
   * year earlier than the valuation date's year.
   */
  static LocalDate valuationDate(LocalDate asOf, LocalDate priceDate, int targetYear) {
    if (priceDate == null || !priceDate.isAfter(asOf) || priceDate.getYear() > targetYear) {
      return asOf;
    }
    return priceDate;
  }

  /**
   * Builds the validated domain object from the flat request. Missing fields raise
   * IllegalArgumentException with a user-facing message (mapped to 400); the domain constructors
   * add their own checks on top.
   */
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
    return new SotpAssumptions(
        request.asOf(),
        request.targetYear(),
        request.discountRate(),
        request.targetShares(),
        request.targetNetCash(),
        segments);
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
        if (c == null
            || c.volume() == null
            || c.price() == null
            || c.netMargin() == null
            || c.pe() == null) {
          throw new IllegalArgumentException(
              "业务线「" + s.name() + "」的 " + scenario + " 情景缺量、价、净利率或本益比");
        }
        cases.put(
            scenario, new SotpSegment.SegmentCase(c.volume(), c.price(), c.netMargin(), c.pe()));
      }
    }
    return new SotpSegment(s.name(), s.scopeNote(), cases);
  }

  private SotpScenario scenario(String key, String segmentName) {
    try {
      return SotpScenario.valueOf(key.trim().toUpperCase());
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "业务线「" + segmentName + "」的情景名非法：" + key + "（只接受 BEAR / BASE / BULL）");
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
      return json.readValue(raw, new TypeReference<List<SegmentRequest>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("业务线假设反序列化失败：" + e, e);
    }
  }
}
