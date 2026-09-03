package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.TrdCommon;
import com.futu.openapi.pb.TrdGetAccList;
import org.jdkxx.trader.domain.AccountKind;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.Broker;
import org.jdkxx.trader.domain.Market;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 富途交易业务账户 → 领域账户引用。只保留富途证券（香港）的实盘账户与全部模拟账户，跳过已禁用的。
 */
public final class FutuAccounts {

    private FutuAccounts() {
    }

    public static List<AccountRef> map(TrdGetAccList.Response rsp) {
        List<AccountRef> out = new ArrayList<>();
        if (!rsp.hasS2C()) {
            return out;
        }
        for (TrdCommon.TrdAcc acc : rsp.getS2C().getAccListList()) {
            if (acc.hasAccStatus() && acc.getAccStatus() == TrdCommon.TrdAccStatus.TrdAccStatus_Disabled_VALUE) {
                continue;
            }
            boolean live = acc.getTrdEnv() == TrdCommon.TrdEnv.TrdEnv_Real_VALUE;
            if (live && acc.hasSecurityFirm()
                    && acc.getSecurityFirm() != TrdCommon.SecurityFirm.SecurityFirm_FutuSecurities_VALUE) {
                continue;
            }
            out.add(new AccountRef(Broker.FUTU, Long.toString(acc.getAccID()),
                    live ? AccountKind.LIVE : AccountKind.PAPER, markets(acc.getTrdMarketAuthListList())));
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
