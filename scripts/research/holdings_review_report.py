#!/usr/bin/env python3
"""持仓风险面板的复核条件历史验证（3.5.0，ARCHITECTURE §29）。

只调只读 GET 接口（默认开发实例 :8083），不写任何数据；只用标准库：

    python3 scripts/research/holdings_review_report.py [--base http://127.0.0.1:8083] [--from 2007-01-01] [--to 2026-09-30] [SYM ...]

不给代码时取池、持仓与基准（20 年深度）。

问题：复核条件「刚刚成立」那天之后，价格是不是比随便哪天更差？如果是，条件有风险提示价值；如果不是，它只是纪律提醒。
方法沿用入场信号回放统计（§18.6，scripts/research/sentinel_report.py）：
- 对照 = 同一只标的、同一年里任意一天的前瞻收益（均值）；比较的是「触发日前瞻收益 − 对照」；
- 同一市场同一年的样本高度相关，显著性按「年份」整块重抽（保留年内相关），不按样本独立计算；
- 只取条件刚刚成立的那天（edge），同一段下跌不重复计数；
- 已知偏差：池与持仓是今天挑的（事后赢家，幸存者偏差）——它让「之后表现」整体偏好，对照也在同一批标的上算，差值受影响较小但不为零。
"""
import argparse, json, random, statistics, urllib.request
from collections import defaultdict

p = argparse.ArgumentParser()
p.add_argument('--base', default='http://127.0.0.1:8083')
p.add_argument('--from', dest='frm', default='2007-01-01')
p.add_argument('--to', default='2026-09-30')
p.add_argument('--boot', type=int, default=20000)
p.add_argument('symbols', nargs='*')
a = p.parse_args()

TRIGGERS = ['BREAK_CONFIRMED', 'DEATH_CROSS', 'CHANDELIER_BREACH']
LABEL = {'BREAK_CONFIRMED': '破位确认（连续 3 天收盘低于 200 日均线）', 'DEATH_CROSS': '死叉（50 日均线下穿 200 日均线）',
         'CHANDELIER_BREACH': '跌破吊灯线（22 日最高 − 3×ATR14）'}
HORIZONS = [5, 20, 60]


def get(path):
    with urllib.request.urlopen(a.base + path, timeout=600) as r:
        return json.load(r)


def year_block_ci(values_by_year, stat=statistics.fmean, n=a.boot, seed=20261030):
    """整年重抽：每轮从有样本的年份里有放回抽同样多个年份，合并其全部样本后求统计量。返回 (下限, 上限, 双侧 p)。"""
    rnd = random.Random(seed)
    years = [y for y, v in values_by_year.items() if v]
    if len(years) < 2:
        return None
    out = []
    for _ in range(n):
        pool = []
        for _ in years:
            pool.extend(values_by_year[rnd.choice(years)])
        out.append(stat(pool))
    out.sort()
    le0 = sum(1 for x in out if x <= 0) / n
    return out[int(0.025 * n)], out[int(0.975 * n) - 1], min(le0, 1 - le0) * 2


def fmt_ci(ci):
    if ci is None:
        return '样本不足'
    lo, hi, pv = ci
    return f'[{lo:+.2%}, {hi:+.2%}]，p≈{pv:.3f}'


symbols = a.symbols
if not symbols:
    symbols = sorted({p['symbol'] for p in get('/api/pool')})

events = {t: {h: defaultdict(list) for h in HORIZONS} for t in TRIGGERS}     # trigger → h → year → [diff]
raw = {t: {h: [] for h in HORIZONS} for t in TRIGGERS}                         # trigger → h → [前瞻收益]
counts = defaultdict(lambda: defaultdict(int))                                 # trigger → symbol → 次数
covered = {}
for sym in symbols:
    try:
        days = get(f'/api/account/holdings-review/history/{sym}?from={a.frm}&to={a.to}')
    except Exception as e:  # noqa: BLE001 — 研究脚本：取不到就跳过并说明
        print(f'- {sym}：取不到（{e}），跳过')
        continue
    if not days:
        continue
    covered[sym] = (days[0]['date'], days[-1]['date'], len(days))
    close = [d['close'] for d in days]
    year = [d['date'][:4] for d in days]
    n = len(days)
    for h in HORIZONS:
        # 对照：同标的同年份所有能算前瞻收益的日子的均值
        by_year = defaultdict(list)
        for i in range(n - h):
            by_year[year[i]].append(close[i + h] / close[i] - 1)
        control = {y: statistics.fmean(v) for y, v in by_year.items() if v}
        for i, d in enumerate(days):
            if i + h >= n:
                break
            for t in d['edges']:
                if t not in events:
                    continue
                r = close[i + h] / close[i] - 1
                raw[t][h].append(r)
                events[t][h][year[i]].append(r - control[year[i]])
                if h == HORIZONS[0]:
                    counts[t][sym] += 1

print(f'# 持仓复核条件历史验证（{a.frm} ~ {a.to}，{len(covered)} 只）\n')
print('标的与覆盖：' + '、'.join(f'{s}（{v[0][:4]}~{v[1][:4]}）' for s, v in covered.items()) + '\n')
for t in TRIGGERS:
    total = sum(counts[t].values())
    print(f'## {LABEL[t]}\n')
    print(f'- 刚刚成立 {total} 次（{len(counts[t])} 只标的）\n')
    print('| 前瞻 | 次数 | 触发后平均收益 | 其中为负 | 相对同标的同年份任意一天 | 年份分块 95% 区间 |')
    print('| --- | --- | --- | --- | --- | --- |')
    for h in HORIZONS:
        rs = raw[t][h]
        diffs = [x for v in events[t][h].values() for x in v]
        if not rs:
            print(f'| {h} 日 | 0 | — | — | — | — |')
            continue
        neg = sum(1 for r in rs if r < 0) / len(rs)
        print(f'| {h} 日 | {len(rs)} | {statistics.fmean(rs):+.2%} | {neg:.0%} | {statistics.fmean(diffs):+.2%} | '
              f'{fmt_ci(year_block_ci(events[t][h]))} |')
    print()
print('读法：「相对同标的同年份任意一天」显著为负（区间整个在 0 以下），才说明条件成立之后确实更差、有风险提示价值；'
      '区间含 0 就只是纪律提醒，不能当作预测。')
