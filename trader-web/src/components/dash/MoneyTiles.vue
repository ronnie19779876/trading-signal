<script setup lang="ts">
import { computed, ref } from 'vue'
import type { LiveView, SnapshotView } from '../../api/account'
import type { Session } from '../../composables/useMarketClock'
import { fmtEt, money, signed, timeEt, trend } from '../../lib/format'

/**
 * 资金区：五个大数字 + 副行 + 折叠"更多"。
 *
 * <b>全部是盈透原值，不做折算</b>（2026-09-19 用户要求与盈透 App 一致）：净值、现金、可用资金、股票市值来自盈透账户汇总
 * （约 3 分钟一推，可能比 App 晚），当日 / 浮动 / 已实现盈亏来自盈透账户盈亏（按变化秒级推）。
 * 各部分更新时间不同步，"更多"里分开写，不强行对齐。
 * 拿不到实时（网关未连、未启用）时退回最近一份收盘快照，并在标题旁写明。
 */
const props = defineProps<{ view: SnapshotView | null; live: LiveView | null; session: Session; loaded: boolean }>()
const more = ref(false)

/** 有可显示的实时数据：LIVE，或断线但还留着最后收到的数。 */
const useLive = computed(() => {
  const l = props.live
  return !!l && !!l.money && (l.status === 'LIVE' || l.status === 'DISCONNECTED')
})

const extSession = computed(() => (props.session === 'PRE' ? '盘前' : props.session === 'AFTER' ? '盘后' : props.session === 'CLOSED' ? '休市' : null))
const lastUpdate = computed(() => {
  const l = props.live
  const ts = [l?.pnl?.updatedAt, l?.money?.updatedAt].filter(Boolean) as string[]
  return ts.length ? ts.sort().at(-1)! : null
})
</script>

<template>
  <section class="panel">
    <div class="panel__head">
      <h3>账户</h3>
      <template v-if="useLive">
        <el-tag v-if="live!.status === 'LIVE'" size="small" type="success" effect="plain" class="live">
          <span class="dot" />实时 · {{ timeEt(lastUpdate) }}
        </el-tag>
        <el-tooltip v-else :content="live!.detail ?? ''" placement="bottom">
          <el-tag size="small" type="warning" effect="plain">断线 · 显示最后收到的数据</el-tag>
        </el-tooltip>
        <span class="panel__src">盈透 · {{ live!.accountMask }}<template v-if="extSession"> · 当前{{ extSession }}，盈亏按{{ extSession === '休市' ? '最后成交' : extSession }}价</template></span>
      </template>
      <template v-else>
        <el-tag v-if="live?.status === 'WARMING'" size="small" type="info" effect="plain">实时数据预热中…</el-tag>
        <el-tooltip v-else-if="live?.status === 'UNAVAILABLE'" :content="live.detail ?? ''" placement="bottom">
          <el-tag size="small" type="info" effect="plain">实时不可用</el-tag>
        </el-tooltip>
        <span v-if="view" class="panel__src">
          收盘口径（{{ view.snapshot.asOfDate }} 快照，{{ view.snapshot.accountMask }}）
        </span>
      </template>
      <span class="panel__grow" />
      <el-tag v-if="view" size="small" effect="plain" :type="view.snapshot.reconStatus === 'OK' ? 'success' : 'warning'">
        对账 {{ view.snapshot.reconStatus }}
      </el-tag>
      <router-link to="/account" class="more-link">账户详情 →</router-link>
    </div>

    <!-- 实时 -->
    <template v-if="useLive">
      <div class="tiles">
        <div class="tile">
          <span class="tile__l">
            净值（{{ live!.currency ?? 'USD' }}）
            <el-tooltip placement="bottom-start">
              <template #content>
                <div style="max-width: 300px">
                  盈透账户汇总的 NetLiquidation 原值。盈透约 3 分钟才推一次，可能比 App 晚几分钟；
                  本次更新于 {{ timeEt(live!.money?.updatedAt) }}（美东）。
                </div>
              </template>
              <span class="q">?</span>
            </el-tooltip>
          </span>
          <b class="tile__v num">{{ money(live!.money?.netLiquidation) }}</b>
        </div>
        <div class="tile">
          <span class="tile__l">当日盈亏</span>
          <b v-if="live!.pnl" class="tile__v num" :class="trend(live!.pnl.daily)">{{ signed(live!.pnl.daily) }}</b>
          <span v-else class="tile__wait">等待盈透推送…</span>
        </div>
        <div class="tile">
          <span class="tile__l">累计浮盈</span>
          <b v-if="live!.pnl" class="tile__v num" :class="trend(live!.pnl.unrealized)">{{ signed(live!.pnl.unrealized) }}</b>
          <span v-else class="tile__wait">等待盈透推送…</span>
        </div>
        <div class="tile">
          <span class="tile__l">总现金</span>
          <b class="tile__v num">{{ money(live!.money?.totalCash) }}</b>
        </div>
        <div class="tile">
          <span class="tile__l">
            可用资金
            <el-tooltip placement="bottom-start">
              <template #content>
                <div style="max-width: 300px">
                  盈透口径 AvailableFunds：按保证金规则还能开多少新仓，持仓证券本身可作担保，所以会远大于总现金；
                  <b>总现金才是账上真正的现金</b>。
                </div>
              </template>
              <span class="q">?</span>
            </el-tooltip>
          </span>
          <b class="tile__v num">{{ money(live!.money?.availableFunds) }}</b>
        </div>
      </div>
      <div class="subrow">
        <span>股票市值 <b class="num">{{ money(live!.money?.stockMarketValue) }}</b></span>
        <span>应计股息 <b class="num">{{ money(live!.money?.accruedDividend) }}</b></span>
        <span>当日已实现 <b class="num" :class="trend(live!.pnl?.realized)">{{ signed(live!.pnl?.realized) }}</b></span>
        <a class="toggle" @click="more = !more">{{ more ? '收起' : '更多' }}</a>
        <template v-if="more">
          <span>购买力 <b class="num">{{ money(live!.money?.buyingPower) }}</b></span>
          <span>剩余流动性 <b class="num">{{ money(live!.money?.excessLiquidity) }}</b></span>
          <span>持仓总市值 <b class="num">{{ money(live!.money?.grossPositionValue) }}</b></span>
          <!-- 三个时间分开写：它们本来就不同步，对齐反而是撒谎 -->
          <span class="ts">资金 {{ timeEt(live!.money?.updatedAt) }}（约 3 分钟一推）· 盈亏 {{ timeEt(live!.pnl?.updatedAt) }}</span>
        </template>
      </div>
      <p v-if="live!.lastError" class="warn">{{ live!.lastError }}</p>
    </template>

    <!-- 收盘快照 -->
    <div v-else-if="!view" class="muted empty">{{ loaded ? '还没有账户快照（每个交易日美东 18:00 自动拍）' : '加载中…' }}</div>
    <template v-else>
      <div class="tiles">
        <div class="tile">
          <span class="tile__l">净值（{{ view.snapshot.currency ?? '—' }}）</span>
          <b class="tile__v num">{{ money(view.snapshot.netLiquidation) }}</b>
        </div>
        <div class="tile">
          <span class="tile__l">较上一交易日（含出入金）</span>
          <b class="tile__v num" :class="trend(view.change?.netLiquidationChange)">
            {{ view.change ? signed(view.change.netLiquidationChange) : '—' }}
          </b>
        </div>
        <div class="tile">
          <span class="tile__l">累计浮盈</span>
          <b class="tile__v num" :class="trend(view.snapshot.unrealizedPnl)">{{ signed(view.snapshot.unrealizedPnl) }}</b>
        </div>
        <div class="tile">
          <span class="tile__l">总现金</span>
          <b class="tile__v num">{{ money(view.snapshot.totalCash) }}</b>
        </div>
        <div class="tile">
          <span class="tile__l">可用资金</span>
          <b class="tile__v num">{{ money(view.snapshot.availableFunds) }}</b>
        </div>
      </div>
      <div class="subrow">
        <span>股票市值 <b class="num">{{ money(view.snapshot.stockMarketValue) }}</b></span>
        <span>应计股息 <b class="num">{{ money(view.snapshot.accruedDividend) }}</b></span>
        <span>已实现盈亏 <b class="num" :class="trend(view.snapshot.realizedPnl)">{{ signed(view.snapshot.realizedPnl) }}</b></span>
        <span v-if="view.change">持仓价差 <b class="num" :class="trend(view.change.positionPnl)">{{ signed(view.change.positionPnl) }}</b>
          <el-tag v-if="view.change.positionsChanged" size="small" type="info" class="approx">有买卖，近似</el-tag></span>
        <a class="toggle" @click="more = !more">{{ more ? '收起' : '更多' }}</a>
        <template v-if="more">
          <span>购买力 <b class="num">{{ money(view.snapshot.buyingPower) }}</b></span>
          <span>剩余流动性 <b class="num">{{ money(view.snapshot.excessLiquidity) }}</b></span>
          <span>持仓总市值 <b class="num">{{ money(view.snapshot.grossPositionValue) }}</b></span>
          <span class="ts">拍于 {{ fmtEt(view.snapshot.takenAt) }}</span>
        </template>
      </div>
    </template>
  </section>
</template>

<style scoped>
.tiles { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; }
.tile { display: flex; flex-direction: column; gap: 4px; min-width: 0; }
.tile__l { font-size: 12px; color: var(--el-text-color-secondary); }
.tile__v { font-size: 22px; font-weight: 600; letter-spacing: -0.3px; }
.tile__wait { font-size: 13px; color: var(--el-text-color-placeholder); line-height: 29px; }
.q {
  display: inline-block; width: 13px; height: 13px; line-height: 13px; text-align: center; border-radius: 50%;
  background: var(--el-fill-color); color: var(--el-text-color-secondary); font-size: 10px; cursor: help;
}
.subrow {
  margin-top: 10px; padding-top: 8px; border-top: 1px dashed var(--el-border-color-lighter);
  font-size: 12px; color: var(--el-text-color-secondary); display: flex; flex-wrap: wrap; gap: 16px; align-items: center;
}
.subrow b { color: var(--el-text-color-primary); font-weight: 600; }
.subrow b.up { color: var(--tr-up); }
.subrow b.down { color: var(--tr-down); }
.approx { margin-left: 4px; }
.toggle { cursor: pointer; color: var(--el-color-primary); }
.ts { color: var(--el-text-color-placeholder); }
.more-link { font-size: 12px; color: var(--el-color-primary); text-decoration: none; }
.empty { padding: 12px 0; }
.warn { margin: 6px 0 0; font-size: 12px; color: var(--el-color-warning); }
.live .dot {
  display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: var(--tr-up); margin-right: 5px;
  vertical-align: 1px; animation: pulse 2s ease-in-out infinite;
}
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.35; } }
@media (max-width: 900px) { .tiles { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
