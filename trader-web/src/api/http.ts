import axios from 'axios'

// 同源：开发期由 vite proxy 转发到 8083，生产随 jar 提供。
// X-Trader-Client：服务端对缺这个头的写请求返回 403（挡跨站 POST，见 LocalRequestGuardFilter）
export const http = axios.create({ timeout: 10_000, headers: { 'X-Trader-Client': 'web' } })
