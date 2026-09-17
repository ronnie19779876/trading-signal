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

