package org.jdkxx.trader.gateway.futu.mapper;

import com.futu.openapi.pb.TrdCommon;
import com.futu.openapi.pb.TrdGetAccList;
import org.jdkxx.trader.domain.AccountKind;
import org.jdkxx.trader.domain.AccountRef;
import org.jdkxx.trader.domain.Market;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FutuAccountsTest {

    private static TrdCommon.TrdAcc acc(long id, int env, int firm, int status, int... markets) {
        TrdCommon.TrdAcc.Builder b = TrdCommon.TrdAcc.newBuilder().setTrdEnv(env).setAccID(id).setSecurityFirm(firm).setAccStatus(status);
        for (int m : markets) {
            b.addTrdMarketAuthList(m);
        }
        return b.build();
    }

    @Test
    void 只保留富途证券实盘与模拟账户并映射市场() {
        TrdGetAccList.Response rsp = TrdGetAccList.Response.newBuilder().setRetType(0)
                .setS2C(TrdGetAccList.S2C.newBuilder()
                        .addAccList(acc(1001, 1, 1, 0, 1, 2, 4))          // 实盘，港美
                        .addAccList(acc(1002, 0, 0, 0, 2))                // 模拟，美
                        .addAccList(acc(1003, 1, 2, 0, 2))                // moomoo US 实盘 → 跳过
                        .addAccList(acc(1004, 1, 1, 1, 1)))               // 已禁用 → 跳过
                .build();

        List<AccountRef> out = FutuAccounts.map(rsp);

        assertThat(out).hasSize(2);
        assertThat(out.get(0).kind()).isEqualTo(AccountKind.LIVE);
        assertThat(out.get(0).markets()).containsExactlyInAnyOrder(Market.HK, Market.US);
        assertThat(out.get(1).kind()).isEqualTo(AccountKind.PAPER);
        assertThat(out.get(1).accountId()).isEqualTo("1002");
        assertThat(out.get(0).toString()).doesNotContain("1001");
    }
}
