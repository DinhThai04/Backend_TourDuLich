-- Additive Auth migration. Apply before deploying revision-aware profile writes.
SET @has_avatar_version = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'users'
      AND column_name = 'avatar_version'
);

SET @migration_sql = IF(
    @has_avatar_version = 0,
    'ALTER TABLE users ADD COLUMN avatar_version BIGINT NOT NULL DEFAULT 0',
    'SELECT 1'
);

PREPARE auth_avatar_revision_migration FROM @migration_sql;
EXECUTE auth_avatar_revision_migration;
DEALLOCATE PREPARE auth_avatar_revision_migration;
