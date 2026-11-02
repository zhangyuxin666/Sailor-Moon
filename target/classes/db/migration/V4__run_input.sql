-- T3: preserve supplemental user input for a paused run.
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS input_json TEXT;
