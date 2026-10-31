import { http } from './http'
import type { GatewayView } from './gateways'

export interface DatabaseStatus {
  enabled: boolean
  database: string | null
  serverVersion: string | null
  marker: string | null
  detail: string
}

export interface AiStatus {
  configured: boolean
  model: string
  detail: string
}

export interface SystemInfo {
  application: string
  version: string
  buildTime: string | null
  environment: string
  serverTime: string
  database: DatabaseStatus
  gateways: GatewayView[]
  ai: AiStatus
}

export interface HealthComponent {
  status: string
  details?: Record<string, unknown>
}

export interface Health {
  status: 'UP' | 'DEGRADED' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN'
  /** Components: db, diskSpace, gateways, jobs (latest run of each job type, values like "OK @ time: summary") and so on */
  components?: Record<string, HealthComponent>
}

export async function getSystemInfo(): Promise<SystemInfo> {
  return (await http.get<SystemInfo>('/api/system/info')).data
}

export async function getHealth(): Promise<Health> {
  return (await http.get<Health>('/actuator/health')).data
}
