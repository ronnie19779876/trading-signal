<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAutoRefresh } from '../../composables/useAutoRefresh'
import { usePager } from '../../composables/usePager'
import { errMsg, fmtEt } from '../../lib/format'
import { JOB_LABEL, JOB_STATUS, duration, jobLabel, triggerLabel } from '../../lib/system'
import {
  backfillPending, cancelJob, getCoverage, getJobs, refreshRehab, refreshUniverse, runIncrement, syncUniverse,
  type JobRun, type RunningJob,
} from '../../api/marketdata'

/**
 * 跑批：当前作业（可取消）、手动触发、作业记录（中文名、耗时、分页，可按作业类型筛）。5 秒刷新。
 * 同一时刻只跑一个作业，冲突时后端 409。
 */
const running = ref<RunningJob | null>(null)
const jobs = ref<JobRun[]>([])
const universeSize = ref<number | null>(null)
const kind = ref('')

async function refresh() {
  try {
    const j = await getJobs(200)
    running.value = j.running && 'id' in j.running ? (j.running as RunningJob) : null
    jobs.value = j.recent
  } catch {
    // 下一轮再试
  }
}
useAutoRefresh(refresh, 5000)
getCoverage().then((c) => (universeSize.value = c.universeSize)).catch(() => {})

async function run(label: string, action: () => Promise<{ jobId: number }>) {
  try {
    const r = await action()
    ElMessage.success(`${label}：作业 #${r.jobId} 已开始`)
    await refresh()
  } catch (e) {
    ElMessage.error(`${label}失败：` + errMsg(e))
  }
}
async function confirmRun(label: string, hint: string, action: () => Promise<{ jobId: number }>) {
  try {
    await ElMessageBox.confirm(hint, label, { confirmButtonText: '开始', cancelButtonText: '取消', type: 'warning' })
  } catch {
    return
  }
  await run(label, action)
}
async function cancel() {
  try {
    await cancelJob()
    ElMessage.info('已请求取消，作业会在下一批边界停下')
  } catch (e) {
    ElMessage.error(errMsg(e))
  }
}

/** 标的数量取自覆盖统计，别在文案里写死——写死的那个数字已经过期过一次。 */
const rehabHint = computed(() => `对全量 ${universeSize.value ?? '—'} 只各调一次复权因子，约 5 分钟，不占历史额度。`)

const kinds = computed(() => [...new Set(jobs.value.map((j) => j.job))].sort((a, b) => jobLabel(a).localeCompare(jobLabel(b), 'zh')))
const filtered = computed(() => jobs.value.filter((j) => !kind.value || j.job === kind.value))
const { page, pageSize, paged, total } = usePager(filtered, 20, [kind])
</script>

<template>
  <div class="tab">
    <section class="panel">
      <div class="panel__head">
        <h3>手动触发</h3>
        <span class="panel__src">定时作业每天自动跑，一般不用手动；同一时刻只跑一个作业</span>
      </div>
      <div class="actions">
        <el-button size="small" @click="run('每日增量', runIncrement)">每日增量</el-button>
        <el-button size="small" @click="run('成分股同步', syncUniverse)">成分股同步</el-button>
        <el-button size="small" @click="confirmRun('全量轮转拉 K 线', '对全量标的按批订阅并拉取 1000 根日 K，约 8 分钟，不占历史额度。生产在跑实时订阅时，开发实例不要跑。', () => refreshUniverse(1000))">全量拉 1000 根</el-button>
        <el-button size="small" @click="confirmRun('深度回补', '对池与持仓里还没有 20 年深度的标的依次回补，每只占 1 个历史额度。', backfillPending)">深度回补池与持仓</el-button>
        <el-button size="small" @click="confirmRun('全量复权因子', rehabHint, () => refreshRehab(true))">全量复权因子</el-button>
        <span class="muted">估值与财报在基本面页的"维护"里，账户快照在账户页的"维护"里</span>
      </div>
      <el-alert v-if="running" type="info" :closable="false" show-icon class="running">
        <template #title>
          作业 #{{ running.id }} {{ jobLabel(running.job) }} 运行中（{{ triggerLabel(running.trigger) }}，开始于 {{ fmtEt(running.startedAt) }}）：{{ running.progress || '…' }}
          <el-button size="small" type="danger" link @click="cancel">取消</el-button>
        </template>
      </el-alert>
    </section>

    <section class="panel">
      <div class="panel__head">
        <h3>作业记录</h3>
        <span class="panel__src">最近 200 条，最新的在上</span>
        <span class="panel__grow" />
        <el-select v-model="kind" size="small" clearable placeholder="全部作业" style="width: 160px">
          <el-option v-for="k in kinds" :key="k" :label="JOB_LABEL[k] ?? k" :value="k" />
        </el-select>
      </div>
      <div class="dtable-wrap">
        <table class="dtable">
          <thead>
            <tr><th>#</th><th>作业</th><th>触发</th><th>开始</th><th class="r">耗时</th><th>结果</th><th>摘要</th></tr>
          </thead>
          <tbody>
            <tr v-for="j in paged" :key="j.id">
              <td class="muted-cell">{{ j.id }}</td>
              <td><b>{{ jobLabel(j.job) }}</b></td>
              <td>{{ triggerLabel(j.trigger) }}</td>
              <td class="muted-cell">{{ fmtEt(j.startedAt) }}</td>
              <td class="r num">{{ duration(j.startedAt, j.finishedAt) }}</td>
              <td><el-tag size="small" :type="JOB_STATUS[j.status]?.type ?? 'info'">{{ JOB_STATUS[j.status]?.text ?? j.status }}</el-tag></td>
              <td class="wrap">{{ j.summary ?? '' }}</td>
            </tr>
            <tr v-if="!filtered.length"><td colspan="7" class="empty">暂无作业记录</td></tr>
          </tbody>
        </table>
      </div>
      <div v-if="total > pageSize" class="pager">
        <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :total="total" :page-sizes="[20, 50, 100]"
                       layout="total, sizes, prev, pager, next" size="small" background />
      </div>
    </section>
  </div>
</template>

<style scoped>
.tab { display: flex; flex-direction: column; gap: 12px; }
.running { margin-top: 10px; }
.dtable { font-size: 13px; }
.muted-cell { color: var(--el-text-color-secondary); }
.wrap { white-space: normal; }
.pager { display: flex; justify-content: flex-end; margin-top: 10px; }
</style>
