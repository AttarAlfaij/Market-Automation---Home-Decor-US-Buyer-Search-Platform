-- V5: Add reply-tracking metadata columns for Message-ID based reply detection.
-- Idempotent: each column/index is only added if it does not already exist.

DELIMITER //

DROP PROCEDURE IF EXISTS add_column_if_not_exists //
CREATE PROCEDURE add_column_if_not_exists(
    IN tbl VARCHAR(64),
    IN col VARCHAR(64),
    IN definition TEXT
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = tbl
          AND COLUMN_NAME = col
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', definition);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //

DROP PROCEDURE IF EXISTS add_index_if_not_exists //
CREATE PROCEDURE add_index_if_not_exists(
    IN tbl VARCHAR(64),
    IN idx VARCHAR(64),
    IN col VARCHAR(64)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = tbl
          AND INDEX_NAME = idx
    ) THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD INDEX `', idx, '` (`', col, '`)');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //

DELIMITER ;

CALL add_column_if_not_exists('email_logs', 'message_id', 'VARCHAR(255) NULL');
CALL add_column_if_not_exists('email_logs', 'in_reply_to', 'VARCHAR(255) NULL');
CALL add_column_if_not_exists('email_logs', 'references_header', 'VARCHAR(500) NULL');
CALL add_column_if_not_exists('campaign_responses', 'message_id', 'VARCHAR(255) NULL');
CALL add_index_if_not_exists('campaign_responses', 'idx_cr_message_id', 'message_id');

DROP PROCEDURE add_column_if_not_exists;
DROP PROCEDURE add_index_if_not_exists;
