/** 画图只需要这几个字段；行情页的 DailyBar 与信号页的 ChartBar 都满足。 */
export interface KlineBar {
  tradeDate: string
  open: number
  high: number
  low: number
  close: number
  volume: number
}

export interface ChartMarker {
  time: string
  position: 'aboveBar' | 'belowBar' | 'inBar'
  shape: 'arrowUp' | 'arrowDown' | 'circle' | 'square'
  color: string
  text?: string
}

export interface ChartPriceLine {
  price: number
  title: string
  color: string
  dashed?: boolean
}

/** 价格区间（如支撑区）：画成上下两条虚线。 */
export interface ChartZone {
  bottom: number
  top: number
  title: string
  color?: string
}


/**
 * 图表配色。lightweight-charts 只认具体颜色值，不认 CSS 变量，所以从 :root 上取出来传进去；
 * 主题切换时组件 watch 到变化会重新取一遍（不重取的话暗色下图还是白底）。
 */
export interface ChartTheme {
  text: string
  grid: string
  border: string
  line: string
  up: string
  down: string
  upFill: string
  downFill: string
}

export function chartTheme(): ChartTheme {
  const s = getComputedStyle(document.documentElement)
  const v = (name: string, fallback: string) => s.getPropertyValue(name).trim() || fallback
  return {
    text: v('--tr-chart-text', '#606266'),
    grid: v('--tr-chart-grid', '#f0f2f5'),
    border: v('--tr-chart-border', '#dcdfe6'),
    line: v('--tr-chart-line', '#409eff'),
    up: v('--tr-up', '#ef5350'),
    down: v('--tr-down', '#26a69a'),
    upFill: v('--tr-up-fill', 'rgba(239, 83, 80, 0.5)'),
    downFill: v('--tr-down-fill', 'rgba(38, 166, 154, 0.5)'),
  }
}

/** 两个图表组件共用的基础配置。 */
export function chartLayout(t: ChartTheme) {
  return {
    layout: { background: { color: 'transparent' }, textColor: t.text },
    grid: { vertLines: { color: t.grid }, horzLines: { color: t.grid } },
    rightPriceScale: { borderColor: t.border },
    timeScale: { borderColor: t.border },
  }
}

/** 均线配色：固定三色，避开涨跌红绿与成本线的主题蓝。 */
export const MA_COLORS = ['#e6a23c', '#b37feb', '#13c2c2', '#f56c9f']

/** 收盘价简单均线；前 period−1 根没有值（不画，不补）。 */
export function movingAverage(bars: KlineBar[], period: number): { time: string; value: number }[] {
  const out: { time: string; value: number }[] = []
  let sum = 0
  for (let i = 0; i < bars.length; i++) {
    sum += bars[i].close
    if (i >= period) sum -= bars[i - period].close
    if (i >= period - 1) out.push({ time: bars[i].tradeDate, value: sum / period })
  }
  return out
}
