/** Charting needs only these fields; both the market-data DailyBar and the signals page's ChartBar satisfy it. */
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

/** A price band (e.g. a support zone): drawn as two dotted lines, top and bottom. */
export interface ChartZone {
  bottom: number
  top: number
  title: string
  color?: string
}


/**
 * Chart colors. lightweight-charts only accepts concrete color values, not CSS variables, so they are read from
 * :root and passed in; on a theme switch the components watch the change and read them again (otherwise the chart
 * keeps a white background in dark mode).
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

/** Base options shared by the chart components. */
export function chartLayout(t: ChartTheme) {
  return {
    layout: { background: { color: 'transparent' }, textColor: t.text },
    grid: { vertLines: { color: t.grid }, horzLines: { color: t.grid } },
    rightPriceScale: { borderColor: t.border },
    timeScale: { borderColor: t.border },
  }
}

/** Moving-average colors: a fixed palette that avoids the up/down green/red and the theme blue used by the cost line. */
export const MA_COLORS = ['#e6a23c', '#b37feb', '#13c2c2', '#f56c9f']

/**
 * Simple moving average of the close, as a running sum. The first period-1 bars have no value (not drawn, not
 * back-filled).
 */
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
