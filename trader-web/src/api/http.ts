import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'

// Same origin: in development the Vite proxy forwards to 8083; in production the page ships inside the jar.
// X-Trader-Client: the server answers 403 to any write request without this header (blocks cross-site POSTs, see
// LocalRequestGuardFilter). A browser cannot attach a custom header to a cross-site form post or a "simple" CORS
// request without a preflight, so its presence proves the request came from our own scripts.
// GET /api/account/live needs it too: it really does start an IBKR subscription, so "GET does not change state"
// does not hold for it. This is an instance-level default header, so every request made through `http` carries it.
export const http = axios.create({ timeout: 10_000, headers: { 'X-Trader-Client': 'web' } })

/**
 * Automatic retry for read requests (3.4.3): only for GET, and only on a **timeout or connection failure** (no
 * response received at all); delays of 1 s and 3 s, at most 2 retries.
 * When the server explicitly answered with an error (404, 500, ...) there is no retry -- retrying would give the
 * same result. Write requests are never retried, so a POST can never be executed twice.
 * Background: the local machine reaches the server through an SSH tunnel; an endpoint that takes 15 ms on the
 * server takes 0.5~3 s through the tunnel, and any hiccup on the link pushes it past 10 s (the 2026-10-13
 * dashboard "failed to load").
 */
export const RETRY_DELAYS_MS = [1_000, 3_000]

// The retry count rides on the request config itself: axios hands the config back in error.config, and re-issuing
// it through http.request carries the counter into the new request's config, so a request retries at most twice.
type RetryConfig = InternalAxiosRequestConfig & { __retries?: number }

/**
 * Retry only when no response arrived at all (error.response is unset), the request was not cancelled, it is a GET,
 * and the retry budget is not used up. Any HTTP status, including 5xx, is a real answer and is passed through.
 */
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
