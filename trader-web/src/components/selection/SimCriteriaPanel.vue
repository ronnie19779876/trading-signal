<script setup lang="ts">
import { computed, ref } from 'vue'
import type { SimCriteria } from '../../api/selection'
import { pct } from '../../lib/format'

/**
 * 「稳定盈利」判据（ARCHITECTURE §26.8，用户 2026-10-13 定）：默认收起，标题不露结果，点开才看三条与结论（用户要求）。
 * 未满 12 个月时前两条照算，结论固定为「观察中」。
 */
const props = defineProps<{ criteria: SimCriteria | null | undefined }>()
const open = ref<string[]>([])

const VERDICT: Record<SimCriteria['verdict'], { text: string; type: 'info' | 'success' | 'danger' | 'warning' }> = {
  NOT_STARTED: { text: '未开始（还没有入场）', type: 'info' },
  OBSERVING: { text: '观察中：未满 12 个月，不下结论', type: 'info' },
  PASS: { text: '达标：三条同时满足', type: 'success' },
  FAIL: { text: '未达标', type: 'danger' },
  UNDETERMINED: { text: '判断不了：缺权益或 SPY 数据', type: 'warning' },
}
const mark = (v: boolean | null | undefined) => (v === null || v === undefined ? '—' : v ? '✓' : '✗')
const c = computed(() => props.criteria ?? null)
</script>

<template>
  <el-collapse v-model="open" class="criteria">
    <el-collapse-item name="criteria" title="稳定盈利判据（§26.8）">
      <template v-if="c">
        <table class="dtable">
          <thead><tr><th>判据</th><th class="r">当前</th><th class="r">要求</th><th class="c">满足</th></tr></thead>
          <tbody>
            <tr>
              <td>年化收益</td>
              <td class="r num">{{ pct(c.annualized) }}</td>
              <td class="r num">≥ 6%（目标 6～8%）</td>
              <td class="c">{{ mark(c.annualizedMet) }}</td>
            </tr>
            <tr>
              <td>跑赢 SPY 同期</td>
              <td class="r num">{{ pct(c.accountReturn) }} vs SPY {{ pct(c.spyReturn) }}</td>
              <td class="r num">账户 &gt; SPY（打平不算）</td>
              <td class="c">{{ mark(c.beatSpy) }}</td>
            </tr>
            <tr>
              <td>观察期满 12 个月</td>
              <td class="r num">{{ c.days === null ? '—' : `已运行 ${c.days} 天` }}</td>
              <td class="r num">{{ c.observationEnds ? `${c.observationEnds} 满期` : '—' }}</td>
              <td class="c">{{ c.verdict === 'NOT_STARTED' ? '—' : c.observationMet ? '✓' : '观察中' }}</td>
            </tr>
          </tbody>
        </table>
        <p class="verdict">结论：<el-tag size="small" :type="VERDICT[c.verdict].type" effect="plain">{{ VERDICT[c.verdict].text }}</el-tag>
          <span v-if="c.asOf" class="muted">（截至 {{ c.asOf }} 收盘；自 {{ c.start }} 第一笔入场起）</span></p>
        <p class="muted note">年化 = (期末权益 ÷ 50 万)^(365 ÷ 天数) − 1，闲置现金也算在分母里；SPY 为第一笔入场日开盘到同一收盘日，两边都只算价格、不含分红。
          未满 12 个月的年化波动很大，仅供参考。</p>
      </template>
      <p v-else class="muted">这份数据没有判据（3.4.1 之前生成）。</p>
    </el-collapse-item>
  </el-collapse>
</template>

<style scoped>
.criteria { border-top: 0; }
.dtable { font-size: 13px; }
.verdict { margin: 8px 0 4px; font-size: 13px; }
.note { font-size: 12px; margin: 0; line-height: 1.6; }
</style>
