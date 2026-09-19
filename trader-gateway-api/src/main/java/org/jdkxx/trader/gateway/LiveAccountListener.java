package org.jdkxx.trader.gateway;

import org.jdkxx.trader.domain.AccountPnl;
import org.jdkxx.trader.domain.AccountSummary;
import org.jdkxx.trader.domain.Position;
import org.jdkxx.trader.domain.PositionPnl;

import java.util.List;

/**
 * 实时账户推送。回调在网关的 dispatch 线程上，<b>不得阻塞</b>；同一次订阅的回调按到达顺序串行执行。
 */
public interface LiveAccountListener {

    /** 资金汇总：首批收齐时一次，之后券商约 3 分钟推一次（实测）。 */
    void onSummary(AccountSummary summary);

    /** 账户盈亏：约每 5 秒按变化推送（实测）。 */
    void onPnl(AccountPnl pnl);

    /** 全部持仓：首批收齐时一次，之后每有变化（成交）推一次完整列表。数量为 0 的已剔除。 */
    void onPositions(List<Position> positions);

    /** 单个持仓的盈亏与市值。 */
    void onPositionPnl(PositionPnl pnl);

    /** 订阅被券商拒绝（例如账户汇总超出并发上限 322）。订阅本身不重试，由调用方决定。 */
    default void onLiveError(String what, GatewayException error) {
    }
}
