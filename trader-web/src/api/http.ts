import axios from 'axios'

// 同源：开发期由 vite proxy 转发到 8083，生产随 jar 提供。
// X-Trader-Client：服务端对缺这个头的写请求返回 403（挡跨站 POST，见 LocalRequestGuardFilter）。
// GET /api/account/live 也要——它真的会向盈透发起订阅，「GET 不改状态」对它不成立。这里是实例级默认头，所有请求都带。
export const http = axios.create({ timeout: 10_000, headers: { 'X-Trader-Client': 'web' } })
