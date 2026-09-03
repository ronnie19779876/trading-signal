import axios from 'axios'

// 同源：开发期由 vite proxy 转发到 8083，生产随 jar 提供
export const http = axios.create({ timeout: 10_000 })
