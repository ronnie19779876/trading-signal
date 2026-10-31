import { computed, onBeforeUnmount, ref } from 'vue'
import { getCalendar } from '../api/marketdata'

/**
 * The US/Eastern trading session and the next open. It only drives the header indicator and polling cadence and
 * never takes part in any data calculation.
 *
 * Trading days and half days come from the trading calendar (`trading_day.kind` = 1 is a half day closing at
 * 13:00; verified that 2025-07-03, 11-28 and 12-24 are all 1). If the calendar cannot be loaded it falls back to
 * "every weekday is open", which shows holidays as open but affects no data.
 *
 * All wall-clock math is done on US/Eastern parts produced by Intl, never on the browser's local time zone.
 */
export type Session = 'PRE' | 'REGULAR' | 'AFTER' | 'CLOSED'

export const SESSION_LABEL: Record<Session, string> = { PRE: '盘前', REGULAR: '盘中', AFTER: '盘后', CLOSED: '休市' }

const ET = 'America/New_York'
const WEEKDAY = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

// Module-level singleton: the calendar is fetched once (a few days either side is enough) and shared by the
// header and all pages.
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
      loading = null // try again next time
    })
  return loading
}

interface EtParts {
  date: string
  weekday: number
  minutes: number
  clock: string
}

/**
 * Split an instant into US/Eastern calendar date, weekday and minutes since midnight. Intl does the time-zone and
 * DST conversion; hourCycle h23 avoids the "24:00" that some engines produce for midnight with hour12: false.
 */
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

/**
 * Whether a day is open and whether it is a half day. When the calendar is loaded, a date missing from it is a
 * holiday; when it is not loaded at all, fall back to weekday-based inference.
 */
function dayKind(date: string, weekday: number): 'FULL' | 'HALF' | 'NONE' {
  const m = days.value
  if (m && m.size) {
    if (!m.has(date)) return 'NONE'
    return m.get(date) === 1 ? 'HALF' : 'FULL'
  }
  return weekday === 0 || weekday === 6 ? 'NONE' : 'FULL'
}

/**
 * Calendar arithmetic on a plain YYYY-MM-DD date. Anchoring at 12:00 UTC keeps the result on the same calendar
 * day whatever the browser's time zone and across DST changes, so the UTC date and weekday are the intended ones.
 */
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

  // Sessions in US/Eastern minutes: pre-market 04:00-09:30, regular 09:30 until the close (13:00 on half days,
  // otherwise 16:00), after-hours until 20:00; everything else, and non-trading days, is CLOSED.
  const session = computed<Session>(() => {
    if (today.value === 'NONE') return 'CLOSED'
    const m = et.value.minutes
    if (m >= 4 * 60 && m < 9 * 60 + 30) return 'PRE'
    if (m >= 9 * 60 + 30 && m < closeAt.value) return 'REGULAR'
    if (m >= closeAt.value && m < 20 * 60) return 'AFTER'
    return 'CLOSED'
  })

  /** The next regular-session open: "today 09:30" / "Monday 09:30" (labels are Chinese). */
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
