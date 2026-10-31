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
