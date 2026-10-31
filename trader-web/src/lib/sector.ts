/**
 * Chinese sector names. Index constituents come from Wikipedia: the S&P 500 uses the eleven GICS sectors, while a
 * few Nasdaq-100 members use ICB naming (Technology, Telecommunications); both are mapped. Unknown names are shown
 * as-is, never guessed.
 */
const SECTOR_CN: Record<string, string> = {
  'Communication Services': '通信服务',
  'Consumer Discretionary': '可选消费',
  'Consumer Staples': '必需消费',
  Energy: '能源',
  Financials: '金融',
  'Health Care': '医疗保健',
  Industrials: '工业',
  'Information Technology': '信息技术',
  Materials: '原材料',
  'Real Estate': '房地产',
  Utilities: '公用事业',
  // ICB naming (Nasdaq-100)
  Technology: '信息技术',
  Telecommunications: '通信服务',
}

export function sectorCn(sector: string | null | undefined): string | null {
  if (!sector) return null
  return SECTOR_CN[sector] ?? sector
}
