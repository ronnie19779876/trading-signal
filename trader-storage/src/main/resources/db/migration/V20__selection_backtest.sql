-- 量化选股·历史检验（ARCHITECTURE §30，3.6.0）。只出报告：不写 selection_score，不改生产模型 F1。

-- 时点成分股口径（§30.5）要用指数自己的「加入日期」。since 是本系统首次同步看到它的日子，不是这个。
ALTER TABLE index_constituent ADD COLUMN added_on date;
COMMENT ON COLUMN index_constituent.added_on IS
    '指数自己的加入日期（维基百科成分股表 Date added / Date first added）；来源没给为 NULL。成分股同步写入，来源没给时保留旧值';

CREATE TABLE selection_backtest_run (
    id            bigserial    PRIMARY KEY,
    spec          varchar(16)  NOT NULL,
    candidate     boolean      NOT NULL,
    spec_detail   jsonb        NOT NULL,
    sample_from   date         NOT NULL,
    sample_to     date         NOT NULL,
    holdout       boolean      NOT NULL,
    job_run_id    bigint,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    result        jsonb        NOT NULL
);
CREATE INDEX selection_backtest_run_spec ON selection_backtest_run (spec, holdout);
COMMENT ON TABLE selection_backtest_run IS
    '历史检验的每一次运行，只追加。holdout = 区间触及留出段（2023-01-01 起）的运行，每次都留痕，报告带第几次动用';
