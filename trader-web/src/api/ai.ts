import { http } from './http'

/** Mirrors the "model second opinion (phase 4, step 4)" section of docs/API.md. The model only has a veto. */

export type AiStatus = 'OK' | 'REFUSED' | 'TRUNCATED' | 'INVALID' | 'FAILED' | 'SKIPPED_BUDGET' | 'FAILED_DATA'
export type AiVerdict = 'VETO' | 'ALLOW' | 'ABSENT'
export type Stance = 'BULLISH' | 'NEUTRAL' | 'BEARISH' | 'AVOID'
export type Confidence = 'HIGH' | 'MEDIUM' | 'LOW'

export interface Evidence {
  /** Field path within the input JSON */
  field: string
  value: string
  point: string
}

export interface Judgment {
  stance: Stance
  confidence: Confidence
  summary: string
  bullEvidence: Evidence[]
  bearEvidence: Evidence[]
  risks: string[]
  dataGaps: string[]
  vetoReason: string
}

export interface EvidenceCheck {
  side: 'BULL' | 'BEAR'
  evidence: Evidence
  verified: boolean
  reason: string
}

export interface AiAnalysis {
  id: number
  instrumentId: number
  symbol: string
  tradeDate: string
  purpose: 'SIGNAL_VETO' | 'MANUAL'
  signalId: number | null
  promptVersion: string
  model: string
  reasoningEffort: string | null
  inputHash: string
  input: Record<string, unknown>
  status: AiStatus
  judgment: Judgment | null
  outputText: string | null
  stance: Stance | null
  confidence: Confidence | null
  verdict: AiVerdict
  verdictReason: string | null
  checks: EvidenceCheck[] | null
  verifiedBear: number | null
  unverified: number | null
  error: string | null
  responseId: string | null
  inputTokens: number | null
  cachedTokens: number | null
  outputTokens: number | null
  reasoningTokens: number | null
  latencyMs: number | null
  jobRunId: number | null
  createdAt: string
}

export interface AiSettings {
  configured: boolean
  model: string
  signalVetoEnabled: boolean
  dailyCallLimit: number
  jobBudget: string
  reasoningEffort: string
  promptVersion: string
}

export interface DailyUsage {
  day: string
  rows: number
  calls: number
  ok: number
  failed: number
  skipped: number
  vetoes: number
  inputTokens: number
  cachedTokens: number
  outputTokens: number
  reasoningTokens: number
}

const opt = (o: Record<string, string | number | null | undefined>) =>
  Object.fromEntries(Object.entries(o).filter(([, v]) => v !== null && v !== undefined && v !== ''))

export const aiApi = {
  usage: async (from?: string, to?: string) =>
    (await http.get<{ settings: AiSettings; days: DailyUsage[] }>('/api/ai/usage', { params: opt({ from, to }) })).data,
  list: async (p: { from?: string; to?: string; status?: string; symbol?: string; limit?: number }) =>
    (await http.get<AiAnalysis[]>('/api/ai/analyses', { params: opt(p) })).data,
  find: async (id: number) => (await http.get<AiAnalysis>(`/api/ai/analyses/${id}`)).data,
  /** Calls the model synchronously (about 15~30 s, billed; counts toward the daily limit; an existing conclusion for the same input is reused). A POST, so http.ts never retries it. */
  analyze: async (symbol: string, date: string) =>
    (await http.post<AiAnalysis>('/api/ai/analyses', null, { params: { symbol, date }, timeout: 120_000 })).data,
}
