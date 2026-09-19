/**
 * 行业中文名。成分股来自 Wikipedia：标普 500 用 GICS 十一大行业，纳指 100 里有几只用的是 ICB 口径
 * （Technology、Telecommunications），一并映射。不认识的原样显示，不猜。
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
  // ICB 口径（纳指 100）
  Technology: '信息技术',
  Telecommunications: '通信服务',
}

export function sectorCn(sector: string | null | undefined): string | null {
  if (!sector) return null
  return SECTOR_CN[sector] ?? sector
}
