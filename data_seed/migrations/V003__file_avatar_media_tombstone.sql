-- Additive upgrade for existing File schemas. Apply before deploying account-media detach.
SET @has_deleted_at = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'avatar_media_lock'
      AND column_name = 'deleted_at'
);

SET @migration_sql = IF(
    @has_deleted_at = 0,
    'ALTER TABLE avatar_media_lock ADD COLUMN deleted_at DATETIME(6) NULL',
    'SELECT 1'
);

PREPARE file_avatar_media_tombstone_migration FROM @migration_sql;
EXECUTE file_avatar_media_tombstone_migration;
DEALLOCATE PREPARE file_avatar_media_tombstone_migration;
