-- 环境标记：每个库只被一种环境的实例使用。启动时 EnvironmentGuard 比对本表与 trader.environment，
-- 不一致拒绝启动。只有一行（id = 1）。
CREATE TABLE app_environment (
    id          smallint     PRIMARY KEY CHECK (id = 1),
    name        varchar(8)   NOT NULL CHECK (name IN ('DEV', 'PROD')),
    stamped_at  timestamptz  NOT NULL DEFAULT now(),
    note        text
);

COMMENT ON TABLE app_environment IS '环境标记：库归属 DEV 还是 PROD，由首次连上的空库自动盖章或人工写入';
COMMENT ON COLUMN app_environment.name IS 'DEV | PROD';
COMMENT ON COLUMN app_environment.note IS '盖章原因，便于事后追溯';
