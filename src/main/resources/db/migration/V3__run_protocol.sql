-- V3: 统一工作流状态，并加入编排协议字段。
--
-- 只动 agent_runs（业务工作流），不动 background_jobs（执行队列）：
--   agent_runs      pending / running / waiting_input / succeeded / failed / cancelled
--   background_jobs queued  / running / succeeded / failed
-- 也不动 activities.status，那是活动自身的状态，前端依赖它显示“排队中”。

-- 1. 默认值从 queued 改为 pending。
ALTER TABLE agent_runs ALTER COLUMN status SET DEFAULT 'pending';

-- 2. 历史状态合并：queued 和 retrying 都表示“还没跑完，等待执行”。
UPDATE agent_runs SET status = 'pending' WHERE status IN ('queued', 'retrying');

-- 3. 编排协议字段：上下文、最终结果、取消时间。
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS context_json TEXT;
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS resolution_json TEXT;
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS cancelled_at VARCHAR(40);

-- 4. 状态约束：把合法取值锁死在 6 个之内。
--    Postgres 不支持 ADD CONSTRAINT IF NOT EXISTS，用条件判断保证重复执行安全。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_agent_runs_status'
          AND conrelid = 'agent_runs'::regclass
    ) THEN
        ALTER TABLE agent_runs ADD CONSTRAINT ck_agent_runs_status
            CHECK (status IN ('pending', 'running', 'waiting_input', 'succeeded', 'failed', 'cancelled'));
    END IF;
END
$$;
