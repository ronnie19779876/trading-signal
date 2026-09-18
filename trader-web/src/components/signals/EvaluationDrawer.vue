<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { signalsApi, type Judgement } from '../../api/signals'
import JudgementView from './JudgementView.vue'
import SignalChart from './SignalChart.vue'
import { errMsg } from './format'

/** 某只某天的四门判定（现场计算）加 K 线。 */
const props = defineProps<{ symbol: string | null; date: string | null }>()
const visible = defineModel<boolean>({ default: false })

const j = ref<Judgement | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

async function load() {
  if (!props.symbol || !props.date) return
  loading.value = true
  error.value = null
  try {
    j.value = await signalsApi.evaluate(props.symbol, props.date)
  } catch (e) {
    error.value = errMsg(e)
    j.value = null
  } finally {
    loading.value = false
  }
}

watch(() => [props.symbol, props.date, visible.value], () => { if (visible.value) load() }, { immediate: true })

const e = computed(() => j.value?.evaluation ?? null)
const stop = computed(() => (e.value?.gates.find((g) => g.gate === 'RISK')?.values.stop as number | undefined) ?? null)
</script>

<template>
  <el-drawer v-model="visible" size="65%" :title="`${symbol ?? ''} · ${date ?? ''} 四门判定`" destroy-on-close>
    <div v-loading="loading" class="drawer">
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
      <template v-if="e && symbol && date">
        <el-card v-if="e.status === 'EVALUATED'" shadow="never">
          <SignalChart :symbol="symbol" :as-of="date" :stop="stop" :plus-one-r="e.exitPlan?.plusOneR" :chandelier="e.exitPlan?.chandelierStop"
                       :target="e.exitPlan?.target" :zone="e.hitZone" />
        </el-card>
        <el-card shadow="never">
          <JudgementView :evaluation="e" />
        </el-card>
        <div v-if="j && (j.droppedNonTradingDays.length || j.missingTradingDays.length)" class="muted">
          剔除的非交易日 K 线：{{ j.droppedNonTradingDays.join('、') || '无' }}；缺失交易日：{{ j.missingTradingDays.join('、') || '无' }}
        </div>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer { display: flex; flex-direction: column; gap: 12px; }
</style>
