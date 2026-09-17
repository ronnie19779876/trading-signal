#!/usr/bin/env python3
"""入场哨兵 sentinel-v1 回放统计（ARCHITECTURE §18 步骤 2）。

只调只读 GET 接口（默认开发实例 :8083），不写任何数据；只用标准库。结论可重跑核对：

    python3 scripts/research/sentinel_report.py [--base http://127.0.0.1:8083] [--from 2007-08-01] [--to 2026-09-02] SYM ...

方法约束（沿用 futu-trader 分享稿 §6.6 的教训）：
- 未平仓的纸面交易不计入（浮盈计零）；
- 跨止损宽度的比较用百分比收益，不用 R（止损越紧 R 越小，同等波动的 R 倍数越大）；
- 同一市场同一区间的交易高度相关，显著性一律按"年份"分块重抽（整年重抽，保留年内相关），不按交易独立计算；
- 信号数 ≠ 交易数：并列一份"持仓期间不重复入场"的口径，与分享稿的成交回测可比。
"""
import argparse, json, math, random, statistics, sys, urllib.request
from collections import defaultdict, Counter

p = argparse.ArgumentParser()
p.add_argument('--base', default='http://127.0.0.1:8083')
p.add_argument('--from', dest='frm', default='2007-08-01')
p.add_argument('--to', default='2026-09-02')
p.add_argument('--boot', type=int, default=20000)
p.add_argument('symbols', nargs='+')
a = p.parse_args()


def get(path):
    with urllib.request.urlopen(a.base + path, timeout=600) as r:
        return json.load(r)


def replay(sym, **kw):
    q = '&'.join(f'{k}={v}' for k, v in kw.items())
    return get(f'/api/signals/replay/{sym}?from={a.frm}&to={a.to}&trades=true&{q}')['trades']


def closes(sym):
    bars = get(f'/api/bars/{sym}?from={a.frm}&to=2099-12-31&adjust=forward')
    return {b['tradeDate']: float(b['close']) for b in bars}, {b['tradeDate']: float(b['open']) for b in bars}


def year_block_ci(values_by_year, stat=statistics.fmean, n=a.boot, seed=20260917):
    """整年重抽：每轮从有样本的年份里有放回抽同样多个年份，合并其全部样本后求统计量。"""
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


def fmt_ci(ci, digits=3, pct=False):
    if ci is None:
        return '样本不足'
    f = (lambda x: f'{x:+.{digits}%}') if pct else (lambda x: f'{x:+.{digits}f}')
    return f'[{f(ci[0])}, {f(ci[1])}]，p≈{ci[2]:.3f}'


def ret(t):
    """已实现收益率（含减半仓那一半）：R 倍数 × 每股风险 ÷ 入场价。每股风险 = +1R − 判定日收盘 = (+1R − 止损) / 2。"""
    if t['r'] is None:
        return None
    risk = (t['plusOneR'] - t['stop']) / 2
    return t['r'] * risk / t['entry']


base = {}
half = {}
wide = {}
for s in a.symbols:
    base[s] = replay(s)
    half[s] = replay(s, half='true')
    wide[s] = replay(s, stopAtr='2.5')
    print(f'已取 {s}：{len(base[s])} 条信号', file=sys.stderr)

spy_close, spy_open = closes('SPY')

print(f'# sentinel-v1 回放统计（{a.frm} ~ {a.to}，{len(a.symbols)} 只）\n')
print('纸面交易：次日开盘入场；收盘跌破止损离场；盘中触及 +1R 后吊灯止损（22 日高 − 3×ATR）生效；20 个交易日未触及 +1R 时间止损；'
      '不计成本；未平仓不计。\n')

# ---- 1. 分标的 ----
print('## 1. 分标的（全部信号，允许持仓期间重复入场）\n')
print('| 标的 | 信号 | 已平 | 胜率 | 每笔 R | ΣR |')
print('| --- | --- | --- | --- | --- | --- |')
all_closed = []
for s in a.symbols:
    closed = [t for t in base[s] if t['r'] is not None]
    all_closed += [(s, t) for t in closed]
    if closed:
        wins = sum(1 for t in closed if t['r'] > 0)
        print(f"| {s} | {len(base[s])} | {len(closed)} | {wins / len(closed):.1%} | {statistics.fmean(t['r'] for t in closed):+.3f} | {sum(t['r'] for t in closed):+.2f} |")
    else:
        print(f'| {s} | {len(base[s])} | 0 | – | – | – |')


def summary(title, pairs):
    rs = [t['r'] for _, t in pairs]
    by_year = defaultdict(list)
    for _, t in pairs:
        by_year[t['signalDate'][:4]].append(t['r'])
    n = len(rs)
    mean = statistics.fmean(rs)
    se = statistics.stdev(rs) / math.sqrt(n) if n > 1 else float('nan')
    print(f'\n## {title}\n')
    print(f'- 交易 {n} 笔，胜率 {sum(1 for r in rs if r > 0) / n:.1%}，每笔期望 **{mean:+.3f}R**')
    print(f'- 按交易独立计算的 95% 区间 [{mean - 1.96 * se:+.3f}, {mean + 1.96 * se:+.3f}]（低估不确定性，仅作对照）')
    print(f'- **按年份分块自助法 95% 区间 {fmt_ci(year_block_ci(by_year))}**（有样本的年份 {sum(1 for v in by_year.values() if v)} 个）')
    print('- 离场原因：' + '、'.join(f'{k} {v}' for k, v in Counter(t['reason'] for _, t in pairs).most_common()))
    return by_year


by_year = summary('2. 合计：全部信号', all_closed)


def non_overlapping(trades):
    out, busy_until = [], ''
    for t in sorted(trades, key=lambda x: x['signalDate']):
        if t['entryDate'] > busy_until:
            out.append(t)
            busy_until = t['exitDate'] or '9999'
    return out


nov = [(s, t) for s in a.symbols for t in non_overlapping(base[s]) if t['r'] is not None]
summary('3. 合计：持仓期间不重复入场（与分享稿成交回测同口径）', nov)

print('\n| 年份 | 笔数 | 每笔 R |')
print('| --- | --- | --- |')
for y in sorted(by_year):
    v = by_year[y]
    print(f'| {y} | {len(v)} | {statistics.fmean(v):+.3f} |')


# ---- 4. 配对比较（同一批信号，只换出场；用百分比收益）----
def paired(title, variant):
    diffs = defaultdict(list)
    count = better = 0
    for s in a.symbols:
        v = {t['signalDate']: t for t in variant[s]}
        for t in base[s]:
            u = v.get(t['signalDate'])
            if u is None or ret(t) is None or ret(u) is None:
                continue
            d = ret(u) - ret(t)
            diffs[t['signalDate'][:4]].append(d)
            count += 1
            better += d > 0
    flat = [x for v in diffs.values() for x in v]
    print(f'\n## {title}\n')
    print(f'- 配对 {count} 笔，变体占优 {better} 笔；每笔收益差（变体 − 基准）均值 **{statistics.fmean(flat):+.4%}**')
    print(f'- 按年份分块自助法 95% 区间 {fmt_ci(year_block_ci(diffs), 3, pct=True)}')


paired('4. 配对：+1R 减半仓 对 不减半仓', half)
paired('5. 配对：止损 2.5×ATR 对 2.0×ATR（只改出场）', wide)

# ---- 6. 事件研究：入场后 N 日收益与相对 SPY 超额 ----
print('\n## 6. 事件研究：入场开盘后 N 个交易日的收益（前复权，含分红）\n')
print('| N | 样本 | 平均收益 | 平均超额（对 SPY） | 超额的年份分块 95% 区间 |')
print('| --- | --- | --- | --- | --- |')
for n in (5, 20, 60):
    raw_by_year, ex_by_year = defaultdict(list), defaultdict(list)
    for s in a.symbols:
        cl, op = closes(s)
        dates = sorted(cl)
        pos = {d: i for i, d in enumerate(dates)}
        for t in base[s]:
            i = pos.get(t['entryDate'])
            if i is None or i + n - 1 >= len(dates):
                continue
            d0, d1 = dates[i], dates[i + n - 1]
            if d0 not in spy_close or d1 not in spy_close:
                continue
            if d0 not in spy_open:
                continue
            r = cl[d1] / op[d0] - 1
            spy = spy_close[d1] / spy_open[d0] - 1
            raw_by_year[t['signalDate'][:4]].append(r)
            ex_by_year[t['signalDate'][:4]].append(r - spy)
    flat_r = [x for v in raw_by_year.values() for x in v]
    flat_e = [x for v in ex_by_year.values() for x in v]
    if flat_r:
        print(f'| {n} | {len(flat_r)} | {statistics.fmean(flat_r):+.2%} | {statistics.fmean(flat_e):+.2%} | {fmt_ci(year_block_ci(ex_by_year), 2, pct=True)} |')

# ---- 7. 选择偏差对照：同一标的、同一年份"任意一天入场"的超额作基准 ----
# 池与持仓是今天挑出来的（事后看的赢家），任何入场规则在它们身上都会显得跑赢 SPY。
# 信号的价值只能用"信号入场 − 同标的同年份随便哪天入场"来衡量。
print('\n## 7. 选择偏差对照：信号超额 − 同标的同年份任意一天入场的超额\n')
print('| N | 配对样本 | 信号超额 | 同标的同年份基准 | 差值 | 差值的年份分块 95% 区间 |')
print('| --- | --- | --- | --- | --- | --- |')
series = {s: closes(s) for s in a.symbols}
for n in (5, 20, 60):
    diff_by_year = defaultdict(list)
    sig_ex, base_ex = [], []
    for s in a.symbols:
        cl, op = series[s]
        dates = sorted(d for d in cl if a.frm <= d)
        pos = {d: i for i, d in enumerate(dates)}

        def excess(i):
            if i + n - 1 >= len(dates):
                return None
            d0, d1 = dates[i], dates[i + n - 1]
            if d0 not in spy_open or d1 not in spy_close:
                return None
            return (cl[d1] / op[d0] - 1) - (spy_close[d1] / spy_open[d0] - 1)

        yearly = defaultdict(list)
        for i, d in enumerate(dates):
            e = excess(i)
            if e is not None:
                yearly[d[:4]].append(e)
        for t in base[s]:
            i = pos.get(t['entryDate'])
            e = None if i is None else excess(i)
            year = t['entryDate'][:4]
            if e is None or not yearly.get(year):
                continue
            b = statistics.fmean(yearly[year])
            sig_ex.append(e)
            base_ex.append(b)
            diff_by_year[t['signalDate'][:4]].append(e - b)
    flat = [x for v in diff_by_year.values() for x in v]
    if flat:
        print(f'| {n} | {len(flat)} | {statistics.fmean(sig_ex):+.2%} | {statistics.fmean(base_ex):+.2%} | '
              f'{statistics.fmean(flat):+.2%} | {fmt_ci(year_block_ci(diff_by_year), 2, pct=True)} |')
