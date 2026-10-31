package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.TrdCommon;
import com.futu.openapi.pb.TrdGetAccList;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jdkxx.trader.domain.AccountKind;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Market;

/**
 * Futu trading accounts → domain account references. Keeps only live accounts of Futu Securities
 * (Hong Kong) and all simulated accounts, skipping disabled ones. The account id is passed on
 * unmasked; masking is the job of whoever displays it.
 */
public final class FutuAccounts {

  private FutuAccounts() {}

  public static List<AccountRef> map(TrdGetAccList.Response rsp) {
    List<AccountRef> out = new ArrayList<>();
    if (!rsp.hasS2C()) {
      return out;
    }
    for (TrdCommon.TrdAcc acc : rsp.getS2C().getAccListList()) {
      if (acc.hasAccStatus()
          && acc.getAccStatus() == TrdCommon.TrdAccStatus.TrdAccStatus_Disabled_VALUE) {
        continue;
      }
      boolean live = acc.getTrdEnv() == TrdCommon.TrdEnv.TrdEnv_Real_VALUE;
      if (live
          && acc.hasSecurityFirm()
          && acc.getSecurityFirm() != TrdCommon.SecurityFirm.SecurityFirm_FutuSecurities_VALUE) {
        continue;
      }
      out.add(
          new AccountRef(
              Broker.FUTU,
              Long.toString(acc.getAccID()),
              live ? AccountKind.LIVE : AccountKind.PAPER,
              markets(acc.getTrdMarketAuthListList())));
    }
    return out;
  }

  static Set<Market> markets(List<Integer> auth) {
    EnumSet<Market> set = EnumSet.noneOf(Market.class);
    for (int m : auth) {
      if (m == TrdCommon.TrdMarket.TrdMarket_HK_VALUE) {
        set.add(Market.HK);
      } else if (m == TrdCommon.TrdMarket.TrdMarket_US_VALUE) {
        set.add(Market.US);
      }
    }
    return set;
  }
}
