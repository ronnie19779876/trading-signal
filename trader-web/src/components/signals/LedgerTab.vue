<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { signalsApi, type Ledger, type SignalTrack } from '../../api/signals'
import type { InstrumentView } from '../../api/marketdata'
import SignalDetailDrawer from './SignalDetailDrawer.vue'
import { usePager } from '../../composables/usePager'
import { EXIT_LABEL, ROLE_LABEL, TRACK_LABEL, errMsg, num, pct, signedR, trend } from './format'

/**
 * 纸面账本：按变体 × 来源 × AI 裁决分组的汇总（只列有信号的组）、两个变体的配对差、明细（分页）。
 * 变体：BASE 止损 2.0×ATR，STOP_2_5 止损 2.5×ATR，都不减半仓。胜率与期望只算已平仓。
 */
const props = defineProps<{ universe: InstrumentView[] }>()

const ledger = ref<Ledger | null>(null)
const variant = ref<string>('BASE')
const status = ref<string>('')
const loading = ref(false)
const error = ref<string | null>(null)
const drawer = ref(false)
const pickedId = ref<number | null>(null)

async function load() {
  loading.value = true
  error.value = null
  try {
    ledger.value = await signalsApi.ledger({ variant: variant.value, status: status.value })
  } catch (e) {
    error.value = errMsg(e)
  } finally {
    loading.value = false
  }
}
watch([variant, status], load, { immediate: true })

const AI_LABEL = { VETO: 'AI 否决', ALLOW: 'AI 放行', NONE: '无 AI 结论' } as const
const VARIANT_LABEL: Record<string, string> = { BASE: 'BASE（止损 2.0×ATR）', STOP_2_5: 'STOP_2_5（止损 2.5×ATR）' }
const stats = computed(() => (ledger.value?.stats ?? []).filter((s) => s.total > 0))
const totals = computed(() => {
  const base = stats.value.filter((s) => s.variant === 'BASE')
  const sum = (k: 'total' | 'pending' | 'open' | 'closed') => base.reduce((n, s) => n + s[k], 0)
  return { total: sum('total'), pending: sum('pending'), open: sum('open'), closed: sum('closed') }
})

const entries = computed(() => ledger.value?.entries ?? [])
const { page, pageSize, paged, total } = usePager(entries)
const names = computed(() => new Map(props.universe.map((u) => [u.symbol, u.nameCn ?? u.name ?? null])))

function open(row: { signal: { id: number }; track: SignalTrack }) {
  pickedId.value = row.signal.id
  drawer.value = true
}

defineExpose({ load })
</script>

<template>
  <div v-loading="loading" class="tab">
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />

    <section class="panel">
      <div class="panel__head">
        <h3>汇总</h3>
        <span class="panel__src">已平仓才计入胜率与期望；补跑与实盘分开看。样本少、集中在同一段行情时不能据此下结论（ARCHITECTURE §18.6）</span>
      </div>
      <div class="kv-grid">
        <div class="kv"><label>信号（BASE）</label><b class="num">{{ totals.total }}</b></div>
        <div class="kv"><label>待入场</label><b class="num">{{ totals.pending }}</b></div>
        <div class="kv"><label>持有中</label><b class="num">{{ totals.open }}</b></div>
        <div class="kv"><label>已平仓</label><b class="num">{{ totals.closed }}</b></div>
      </div>
      <div class="dtable-wrap stats">
        <table class="dtable">
          <thead>
            <tr>
              <th>变体</th><th>来源</th><th>AI</th><th class="r">总数</th><th class="r">待入场</th><th class="r">持有中</th><th class="r">已平仓</th>
              <th class="r">胜率</th><th class="r">每笔 R</th><th class="r">每笔收益</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in stats" :key="s.variant + s.origin + s.ai">
              <td>{{ VARIANT_LABEL[s.variant] ?? s.variant }}</td>
              <td>{{ s.origin === 'LIVE' ? '实盘' : '补跑' }}</td>
              <td>{{ AI_LABEL[s.ai] }}</td>
              <td class="r num">{{ s.total }}</td>
              <td class="r num">{{ s.pending }}</td>
              <td class="r num">{{ s.open }}</td>
              <td class="r num">{{ s.closed }}</td>
              <td class="r num">{{ pct(s.winRate, 1) }}</td>
              <td class="r num" :class="trend(s.meanR)">{{ signedR(s.meanR) }}</td>
              <td class="r num" :class="trend(s.meanReturn)">{{ pct(s.meanReturn) }}</td>
            </tr>
            <tr v-if="!stats.length"><td colspan="10" class="empty">还没有纸面交易</td></tr>
          </tbody>
        </table>
      </div>
      <p class="panel__foot">
        配对（同一批已平仓信号）：STOP_2_5 − BASE 每笔收益差
        <b :class="trend(ledger?.pairedMeanReturnDiff)">{{ pct(ledger?.pairedMeanReturnDiff) }}</b>，{{ ledger?.pairedCount ?? 0 }} 笔
      </p>
    </section>

    <section class="panel">
      <div class="panel__head">
        <h3>明细</h3>
        <span class="panel__src">点一行看信号详情</span>
        <span class="panel__grow" />
        <el-radio-group v-model="variant" size="small">
          <el-radio-button value="BASE">BASE</el-radio-button>
          <el-radio-button value="STOP_2_5">STOP_2_5</el-radio-button>
        </el-radio-group>
        <el-select v-model="status" size="small" clearable placeholder="状态" style="width: 110px">
          <el-option v-for="(label, k) in TRACK_LABEL" :key="k" :label="label" :value="k" />
        </el-select>
      </div>
      <div class="dtable-wrap">
        <table class="dtable">
          <thead>
            <tr>
              <th>判定日</th><th>代码</th><th>角色</th><th>状态</th><th>入场</th><th class="r">止损</th><th>离场</th>
              <th class="r">R</th><th class="r">收益</th><th class="r">持有天数</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in paged" :key="row.signal.id + row.track.variant" class="clickable" @click="open(row)">
              <td>{{ row.signal.tradeDate }}</td>
              <td><b>{{ row.signal.symbol }}</b><span v-if="names.get(row.signal.symbol)" class="name">{{ names.get(row.signal.symbol) }}</span></td>
              <td>{{ ROLE_LABEL[row.signal.role] }}</td>
              <td>{{ TRACK_LABEL[row.track.status] }}</td>
              <td class="num">{{ row.track.entryDate ?? '—' }} {{ row.track.entryPrice === null ? '' : num(row.track.entryPrice) }}</td>
              <td class="r num">{{ num(row.track.stop) }}</td>
              <td class="num">
                <template v-if="row.track.exitDate">{{ row.track.exitDate }} {{ num(row.track.exitPrice) }}（{{ EXIT_LABEL[row.track.exitReason ?? 'OPEN'] }}）</template>
                <template v-else>—</template>
              </td>
              <td class="r num" :class="trend(row.track.rMultiple)">{{ signedR(row.track.rMultiple) }}</td>
              <td class="r num" :class="trend(row.track.returnPct)">{{ pct(row.track.returnPct) }}</td>
              <td class="r num">{{ row.track.barsHeld ?? '—' }}</td>
            </tr>
            <tr v-if="!entries.length"><td colspan="10" class="empty">{{ loading ? '加载中…' : '没有符合条件的纸面交易' }}</td></tr>
          </tbody>
        </table>
      </div>
      <div v-if="total > pageSize" class="pager">
        <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" :page-sizes="[20, 50, 100]"
                       layout="total, sizes, prev, pager, next, jumper" size="small" background />
      </div>
    </section>

    <SignalDetailDrawer v-model="drawer" :signal-id="pickedId" @changed="load" />
  </div>
</template>

<style scoped>
.tab { display: flex; flex-direction: column; gap: 12px; }
.stats { margin-top: 10px; }
.dtable { font-size: 13px; }
.name { margin-left: 8px; font-size: 12px; color: var(--el-text-color-secondary); }
.pager { display: flex; justify-content: flex-end; margin-top: 10px; }
.panel__foot b.up { color: var(--tr-up); }
.panel__foot b.down { color: var(--tr-down); }
</style>
