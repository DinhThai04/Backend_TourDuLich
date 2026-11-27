ALTER TABLE operation_audit
  ADD COLUMN actor_type VARCHAR(16),
  ADD COLUMN actor_name VARCHAR(160),
  ADD COLUMN actor_role VARCHAR(100),
  ADD COLUMN action_group VARCHAR(24),
  ADD COLUMN target_label VARCHAR(160),
  ADD COLUMN summary VARCHAR(240),
  ADD COLUMN reason VARCHAR(1000),
  ADD COLUMN before_json JSON,
  ADD COLUMN after_json JSON,
  ADD COLUMN created_at DATETIME(6),
  ADD INDEX ix_admin_audit_group (actor_type,action_group,created_at);
