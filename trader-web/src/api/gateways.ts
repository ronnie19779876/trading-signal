import { http } from './http'

export type GatewayState = 'DISABLED' | 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING' | 'ERROR'

export interface GatewayView {
  broker: 'IBKR' | 'FUTU'
  displayName: string
  role: string
  enabled: boolean
  autoConnect: boolean
  state: GatewayState
  healthy: boolean
  detail: string
  checkedAt: string
  connectedSince: string | null
  lastHeartbeatAt: string | null
  reconnectAttempts: number
  facts: Record<string, string>
}

export interface AccountView {
  broker: string
  maskedId: string
  kind: 'LIVE' | 'PAPER'
  markets: string[]
}

export interface EventView {
  id: number
  broker: string
  event: 'CONNECTED' | 'RECONNECTED' | 'DISCONNECTED' | 'ERROR'
  detail: string
  occurredAt: string
}

export async function getGateways(): Promise<GatewayView[]> {
  return (await http.get<GatewayView[]>('/api/gateways')).data
}

export async function connectGateway(broker: string): Promise<GatewayView> {
  return (await http.post<GatewayView>(`/api/gateways/${broker}/connect`)).data
}

export async function disconnectGateway(broker: string): Promise<GatewayView> {
  return (await http.post<GatewayView>(`/api/gateways/${broker}/disconnect`)).data
}

export async function getAccounts(broker: string): Promise<AccountView[]> {
  return (await http.get<AccountView[]>(`/api/gateways/${broker}/accounts`)).data
}

export async function getEvents(limit = 20): Promise<EventView[]> {
  return (await http.get<EventView[]>('/api/gateways/events', { params: { limit } })).data
}
