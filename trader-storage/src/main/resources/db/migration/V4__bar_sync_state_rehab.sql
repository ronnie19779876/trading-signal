-- 记录每只标的复权因子最近一次刷新时刻：全量标的每周刷新一次（增量作业里挑 7 天以上未刷新的），池与持仓每天刷新。
ALTER TABLE bar_sync_state ADD COLUMN rehab_fetched_at timestamptz;
