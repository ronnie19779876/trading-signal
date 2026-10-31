package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.Common;
import com.futu.openapi.pb.GetGlobalState;
import com.futu.openapi.pb.QotCommon;
import java.util.Map;
import java.util.TreeMap;

/**
 * getGlobalState result → facts that are safe to expose (versions, login flags, program and market
 * states; no account or connection details).
 */
public final class FutuStates {

  private FutuStates() {}

  public static Map<String, String> facts(GetGlobalState.S2C s) {
    Map<String, String> m = new TreeMap<>();
    m.put("opendVersion", s.getServerVer() + "." + s.getServerBuildNo());
    m.put("qotLogined", Boolean.toString(s.getQotLogined()));
    m.put("trdLogined", Boolean.toString(s.getTrdLogined()));
    m.put("programStatus", programStatus(s));
    m.put("market.US", marketState(s.getMarketUS()));
    m.put("market.HK", marketState(s.getMarketHK()));
    return m;
  }

  public static String programStatus(GetGlobalState.S2C s) {
    if (!s.hasProgramStatus()) {
      return "UNKNOWN";
    }
    Common.ProgramStatusType t =
        s.getProgramStatus()
            .getType(); // proto2 enum field: the getter returns the enum itself, not an int
    return t == null ? "UNKNOWN" : t.name().replace("ProgramStatusType_", "");
  }

  public static boolean ready(GetGlobalState.S2C s) {
    return !s.hasProgramStatus()
        || s.getProgramStatus().getType() == Common.ProgramStatusType.ProgramStatusType_Ready;
  }

  static String marketState(int value) {
    QotCommon.QotMarketState st = QotCommon.QotMarketState.forNumber(value);
    return st == null ? "UNKNOWN(" + value + ")" : st.name().replace("QotMarketState_", "");
  }
}
