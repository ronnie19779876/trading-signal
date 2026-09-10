-- 定时作业被丢弃时在库里完全没有痕迹：JobService 在已有作业运行时抛异常，
-- 调度器只打一行 WARN，日志随部署清理还会丢。补一个 SKIPPED 状态用于留痕。
-- CATCHUP 触发方式给当天补偿检查用，与 SCHEDULE 区分开，便于事后分辨哪次是补跑的。
ALTER TABLE job_run DROP CONSTRAINT job_run_status_check;
ALTER TABLE job_run ADD CONSTRAINT job_run_status_check
    CHECK (status IN ('RUNNING', 'OK', 'PARTIAL', 'FAILED', 'SKIPPED'));

ALTER TABLE job_run DROP CONSTRAINT job_run_trigger_check;
ALTER TABLE job_run ALTER COLUMN trigger TYPE varchar(12);
ALTER TABLE job_run ADD CONSTRAINT job_run_trigger_check
    CHECK (trigger IN ('MANUAL', 'SCHEDULE', 'CATCHUP'));

COMMENT ON COLUMN job_run.status IS 'RUNNING/OK/PARTIAL/FAILED；SKIPPED=调度重试到底仍被占用而放弃';
COMMENT ON COLUMN job_run.trigger IS 'MANUAL 手工、SCHEDULE 定时、CATCHUP 当天补偿检查补跑';
