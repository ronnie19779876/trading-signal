import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'

// 同源：开发期由 vite proxy 转发到 8083，生产随 jar 提供。
// X-Trader-Client：服务端对缺这个头的写请求返回 403（挡跨站 POST，见 LocalRequestGuardFilter）。
// GET /api/account/live 也要——它真的会向盈透发起订阅，「GET 不改状态」对它不成立。这里是实例级默认头，所有请求都带。
export const http = axios.create({ timeout: 10_000, headers: { 'X-Trader-Client': 'web' } })

/**
 * 读请求的自动重试（3.4.3）：只对 GET、只在**超时或连不上**（没有收到任何响应）时重试，间隔 1 秒、3 秒，最多 2 次。
 * 服务端明确回了错误（404、500……）不重试——重试也是同样结果；写请求一律不重试，避免重复执行。
 * 起因：本机经 SSH 隧道访问服务器，服务端 15 毫秒的接口走隧道要 0.5~3 秒，链路一抖就超过 10 秒（2026-10-13 仪表盘「取数失败」）。
 */
export const RETRY_DELAYS_MS = [1_000, 3_000]

type RetryConfig = InternalAxiosRequestConfig & { __retries?: number }

export function shouldRetry(error: AxiosError): boolean {
  const config = error.config as RetryConfig | undefined
  if (!config || axios.isCancel(error) || error.response) return false
  if ((config.method ?? 'get').toLowerCase() !== 'get') return false
  return (config.__retries ?? 0) < RETRY_DELAYS_MS.length
}

http.interceptors.response.use(undefined, async (error: AxiosError) => {
  if (!shouldRetry(error)) throw error
  const config = error.config as RetryConfig
  const n = config.__retries ?? 0
  config.__retries = n + 1
  await new Promise((resolve) => setTimeout(resolve, RETRY_DELAYS_MS[n]))
  return http.request(config)
})
