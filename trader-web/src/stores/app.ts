import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getHealth, getSystemInfo, type Health, type SystemInfo } from '../api/system'
import { getJobs, type RunningJob } from '../api/marketdata'
import { errMsg } from '../lib/format'

/**
 * 全站共用的运行状态：环境标记、健康、当前作业。
 *
 * 之前行情页与基本面页各自 5 秒轮询一次 /api/jobs，页头又没地方显示"有作业在跑"。
 * 这里统一拉一次，页头常驻显示，页面按需取用。
 */
export const useAppStore = defineStore('app', () => {
  const info = ref<SystemInfo | null>(null)
  const health = ref<string>('未知')
  /** 健康检查全文：各组件与每类作业最近一次（系统页概况用） */
  const healthDetail = ref<Health | null>(null)
  const running = ref<RunningJob | null>(null)
  const error = ref<string | null>(null)

  async function refresh() {
    try {
      const [i, h, j] = await Promise.all([getSystemInfo(), getHealth(), getJobs(1)])
      info.value = i
      health.value = h.status
      healthDetail.value = h
      running.value = j.running && 'id' in j.running ? (j.running as RunningJob) : null
      error.value = null
    } catch (e) {
      error.value = errMsg(e)
    }
  }

  return { info, health, healthDetail, running, error, refresh }
})
