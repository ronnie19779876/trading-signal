import { computed, onBeforeUnmount, ref } from 'vue'
import { getCalendar } from '../api/marketdata'

/**
 * 美东时段与下次开盘。只决定页头那盏灯和刷新节奏，不参与任何数据计算。
 *
 * 交易日与半日取自交易日历（`trading_day.kind` = 1 为半日，13:00 收盘；实测 2025-07-03、11-28、12-24 均为 1）。
 * 日历取不到时退回"工作日都开市"，会把假日误显示为开市，但不影响任何数据。
 */
export type Session = 'PRE' | 'REGULAR' | 'AFTER' | 'CLOSED'

export const SESSION_LABEL: Record<Session, string> = { PRE: '盘前', REGULAR: '盘中', AFTER: '盘后', CLOSED: '休市' }

const ET = 'America/New_York'
const WEEKDAY = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

// 模块级单例：日历只取一次（前后各几天够用），页头与各页共用。
const days = ref<Map<string, number> | null>(null)
let loading: Promise<void> | null = null

function loadCalendar() {
  if (loading) return loading
  const from = new Date(Date.now() - 7 * 86_400_000).toLocaleDateString('en-CA', { timeZone: ET })
  const to = new Date(Date.now() + 30 * 86_400_000).toLocaleDateString('en-CA', { timeZone: ET })
  loading = getCalendar(from, to)
    .then((list) => {
      days.value = new Map(list.map((d) => [d.date, d.kind]))
    })
    .catch(() => {
      days.value = null
      loading = null // 下次再试
    })
  return loading
}

interface EtParts {
  date: string
  weekday: number
  minutes: number
  clock: string
}

function etParts(t: Date): EtParts {
  const f = new Intl.DateTimeFormat('en-CA', {
    timeZone: ET, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
    hourCycle: 'h23', weekday: 'short',
  })
  const p = Object.fromEntries(f.formatToParts(t).map((x) => [x.type, x.value]))
  const wd = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'].indexOf(p.weekday)
  return {
    date: `${p.year}-${p.month}-${p.day}`,
    weekday: wd,
    minutes: Number(p.hour) * 60 + Number(p.minute),
    clock: `${p.hour}:${p.minute}:${p.second}`,
  }
}

/** 某天是否开市、是否半日。日历没有这天（超出范围或没取到）时按工作日推断。 */
function dayKind(date: string, weekday: number): 'FULL' | 'HALF' | 'NONE' {
  const m = days.value
  if (m && m.size) {
    if (!m.has(date)) return 'NONE'
    return m.get(date) === 1 ? 'HALF' : 'FULL'
  }
  return weekday === 0 || weekday === 6 ? 'NONE' : 'FULL'
}

function addDays(date: string, n: number): { date: string; weekday: number } {
  const d = new Date(date + 'T12:00:00Z')
  d.setUTCDate(d.getUTCDate() + n)
  return { date: d.toISOString().slice(0, 10), weekday: d.getUTCDay() }
}

export function useMarketClock() {
  void loadCalendar()
  const now = ref(new Date())
  const timer = window.setInterval(() => (now.value = new Date()), 1000)
  onBeforeUnmount(() => window.clearInterval(timer))

  const et = computed(() => etParts(now.value))
  const today = computed(() => dayKind(et.value.date, et.value.weekday))
  const closeAt = computed(() => (today.value === 'HALF' ? 13 * 60 : 16 * 60))

  const session = computed<Session>(() => {
    if (today.value === 'NONE') return 'CLOSED'
    const m = et.value.minutes
    if (m >= 4 * 60 && m < 9 * 60 + 30) return 'PRE'
    if (m >= 9 * 60 + 30 && m < closeAt.value) return 'REGULAR'
    if (m >= closeAt.value && m < 20 * 60) return 'AFTER'
    return 'CLOSED'
  })

  /** 下一次常规时段开盘："今天 09:30" / "周一 09:30"。 */
  const nextOpen = computed(() => {
    const { date, minutes } = et.value
    if (today.value !== 'NONE' && minutes < 9 * 60 + 30) return '今天 09:30'
    for (let i = 1; i <= 10; i++) {
      const d = addDays(date, i)
      if (dayKind(d.date, d.weekday) !== 'NONE') return `${i === 1 ? '明天' : WEEKDAY[d.weekday]} 09:30`
    }
    return '—'
  })

  const halfDay = computed(() => today.value === 'HALF')

  return { et, session, nextOpen, halfDay }
}
