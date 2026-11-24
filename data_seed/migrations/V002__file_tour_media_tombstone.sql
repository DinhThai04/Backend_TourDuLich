-- Additive upgrade for existing File schemas. Run once per File database before deploying
-- the gallery tombstone code. The fresh-install schema already declares this column.
SET @has_deleted_at = (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'tour_media_lock'
      AND column_name = 'deleted_at'
);

SET @migration_sql = IF(
    @has_deleted_at = 0,
    'ALTER TABLE tour_media_lock ADD COLUMN deleted_at DATETIME(6) NULL',
    'SELECT 1'
);

PREPARE file_tour_media_tombstone_migration FROM @migration_sql;
EXECUTE file_tour_media_tombstone_migration;
DEALLOCATE PREPARE file_tour_media_tombstone_migration;
