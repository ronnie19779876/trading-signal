import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getHealth, getSystemInfo, type Health, type SystemInfo } from '../api/system'
import { getJobs, type RunningJob } from '../api/marketdata'
import { errMsg } from '../lib/format'

/**
 * Site-wide runtime state: environment marker, health, current job.
 *
 * Previously the market-data and fundamentals pages each polled /api/jobs every 5 seconds, and the header had
 * nowhere to show "a job is running". This store fetches it once for everyone; the header always shows it and pages
 * read it as needed.
 * The three requests are combined with Promise.all, so if any one fails nothing is updated and `error` is set.
 */
export const useAppStore = defineStore('app', () => {
  const info = ref<SystemInfo | null>(null)
  const health = ref<string>('未知')
  /** The full health response: every component and the latest run of each job type (used by the system overview) */
  const healthDetail = ref<Health | null>(null)
  const running = ref<RunningJob | null>(null)
  const error = ref<string | null>(null)

  /**
   * The three requests are applied independently: with Promise.all one slow or failed request threw away the
   * other two results, so a health timeout also froze the environment tag and the running-job banner
   * (found in the 2026-10-31 review). Each block keeps its previous value on failure; error lists only the failures.
   */
  async function refresh() {
    const [i, h, j] = await Promise.allSettled([getSystemInfo(), getHealth(), getJobs(1)])
    const failures: string[] = []
    if (i.status === 'fulfilled') info.value = i.value
    else failures.push('系统信息：' + errMsg(i.reason))
    if (h.status === 'fulfilled') {
      health.value = h.value.status
      healthDetail.value = h.value
    } else failures.push('健康：' + errMsg(h.reason))
    if (j.status === 'fulfilled') {
      const r = j.value.running
      running.value = r && 'id' in r ? (r as RunningJob) : null
    } else failures.push('作业：' + errMsg(j.reason))
    error.value = failures.length ? failures.join('；') : null
  }

  return { info, health, healthDetail, running, error, refresh }
})
