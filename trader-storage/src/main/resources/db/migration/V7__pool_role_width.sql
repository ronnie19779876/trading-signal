-- V6 加了 BENCHMARK 角色却没放宽列宽：role 是 varchar(8)，'BENCHMARK' 九个字符存不下，
-- 加基准时报 "value too long for type character varying(8)"。迁移只增不改，这里单独放宽。
ALTER TABLE pool_member ALTER COLUMN role TYPE varchar(12);
