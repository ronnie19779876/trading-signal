import { http } from './http'

export interface DatabaseStatus {
  enabled: boolean
  database: string | null
  serverVersion: string | null
  marker: string | null
  detail: string
}

export interface GatewayView {
  broker: 'IBKR' | 'FUTU'
  displayName: string
  role: string
  state: 'DISABLED' | 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'ERROR'
  healthy: boolean
  detail: string
  checkedAt: string
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

export interface Health {
  status: 'UP' | 'DOWN' | 'OUT_OF_SERVICE' | 'UNKNOWN'
}

export async function getSystemInfo(): Promise<SystemInfo> {
  return (await http.get<SystemInfo>('/api/system/info')).data
}

export async function getHealth(): Promise<Health> {
  return (await http.get<Health>('/actuator/health')).data
}
