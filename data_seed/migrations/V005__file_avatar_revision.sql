-- Additive File migration. Apply before deploying revision-aware avatar uploads.
SET @has_avatar_version = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'avatar_media_lock'
      AND column_name = 'avatar_version'
);

SET @migration_sql = IF(
    @has_avatar_version = 0,
    'ALTER TABLE avatar_media_lock ADD COLUMN avatar_version BIGINT NOT NULL DEFAULT 0',
    'SELECT 1'
);

PREPARE file_avatar_revision_migration FROM @migration_sql;
EXECUTE file_avatar_revision_migration;
DEALLOCATE PREPARE file_avatar_revision_migration;
