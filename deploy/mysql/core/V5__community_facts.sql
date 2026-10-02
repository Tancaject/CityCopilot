-- Likes and follows are durable MySQL facts. Redis data is imported separately while old writers are stopped.
CREATE TABLE IF NOT EXISTS note_like (
    note_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    liked_at BIGINT NOT NULL COMMENT 'Unix epoch milliseconds from the original like',
    PRIMARY KEY (note_id, user_id),
    KEY idx_note_like_earliest (note_id, liked_at, user_id),
    KEY idx_note_like_user (user_id, note_id),
    CONSTRAINT fk_note_like_note FOREIGN KEY (note_id) REFERENCES note(id) ON DELETE CASCADE,
    CONSTRAINT chk_note_like_time CHECK (liked_at > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS community_facts_cutover (
    id TINYINT NOT NULL PRIMARY KEY,
    ready BOOLEAN NOT NULL,
    completed_at DATETIME(3) NULL,
    CONSTRAINT chk_community_cutover_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Re-adoption after a lost Flyway history may encounter V5 objects; never trust a same-name wrong identity.
CREATE TEMPORARY TABLE community_schema_guard (
    valid TINYINT NOT NULL,
    CONSTRAINT chk_community_schema_supported CHECK (valid = 1)
);
INSERT INTO community_schema_guard
SELECT IF(COUNT(*) = 4, 1, 0) FROM (
    SELECT TABLE_NAME, INDEX_NAME
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('note_like', 'community_facts_cutover')
    GROUP BY TABLE_NAME, INDEX_NAME
    HAVING SUM(SUB_PART IS NOT NULL) = 0 AND (
        (TABLE_NAME = 'note_like' AND INDEX_NAME = 'PRIMARY' AND MIN(NON_UNIQUE) = 0
            AND GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'note_id,user_id')
        OR (TABLE_NAME = 'note_like' AND INDEX_NAME = 'idx_note_like_earliest'
            AND GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'note_id,liked_at,user_id')
        OR (TABLE_NAME = 'note_like' AND INDEX_NAME = 'idx_note_like_user'
            AND GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'user_id,note_id')
        OR (TABLE_NAME = 'community_facts_cutover' AND INDEX_NAME = 'PRIMARY' AND MIN(NON_UNIQUE) = 0
            AND GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'id')
    )
) supported_indexes;
INSERT INTO community_schema_guard
SELECT IF(COUNT(*) = 1, 1, 0)
FROM information_schema.REFERENTIAL_CONSTRAINTS r
JOIN information_schema.KEY_COLUMN_USAGE k
  ON k.CONSTRAINT_SCHEMA = r.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME = r.CONSTRAINT_NAME
 AND k.TABLE_NAME = r.TABLE_NAME
WHERE r.CONSTRAINT_SCHEMA = DATABASE() AND r.TABLE_NAME = 'note_like'
  AND k.COLUMN_NAME = 'note_id' AND k.REFERENCED_TABLE_NAME = 'note'
  AND k.REFERENCED_COLUMN_NAME = 'id' AND r.DELETE_RULE = 'CASCADE';
SELECT note_id, user_id, liked_at FROM note_like LIMIT 0;
SELECT id, ready, completed_at FROM community_facts_cutover LIMIT 0;

-- Duplicate rows represent the same directed relation; preserve its earliest physical identity.
DELETE duplicate_relation FROM user_follow duplicate_relation
JOIN user_follow first_relation
  ON duplicate_relation.user_id = first_relation.user_id
 AND duplicate_relation.follow_user_id = first_relation.follow_user_id
 AND duplicate_relation.id > first_relation.id;

SET @community_ddl = IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_follow' AND INDEX_NAME = 'uk_user_follow_pair'),
    'SELECT 1', 'ALTER TABLE user_follow ADD UNIQUE KEY uk_user_follow_pair (user_id, follow_user_id)');
PREPARE community_stmt FROM @community_ddl;
EXECUTE community_stmt;
DEALLOCATE PREPARE community_stmt;
SET @community_ddl = IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_follow' AND INDEX_NAME = 'idx_user_follow_target'),
    'SELECT 1', 'ALTER TABLE user_follow ADD KEY idx_user_follow_target (follow_user_id, user_id)');
PREPARE community_stmt FROM @community_ddl;
EXECUTE community_stmt;
DEALLOCATE PREPARE community_stmt;
SET @community_ddl = IF(EXISTS(SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'note' AND INDEX_NAME = 'idx_note_author_created'),
    'SELECT 1', 'ALTER TABLE note ADD KEY idx_note_author_created (user_id, create_time, id)');
PREPARE community_stmt FROM @community_ddl;
EXECUTE community_stmt;
DEALLOCATE PREPARE community_stmt;
INSERT INTO community_schema_guard
SELECT IF(COUNT(*) = 3, 1, 0) FROM (
    SELECT TABLE_NAME, INDEX_NAME
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('user_follow', 'note')
    GROUP BY TABLE_NAME, INDEX_NAME
    HAVING SUM(SUB_PART IS NOT NULL) = 0 AND (
        (TABLE_NAME = 'user_follow' AND INDEX_NAME = 'uk_user_follow_pair' AND MIN(NON_UNIQUE) = 0
            AND GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'user_id,follow_user_id')
        OR (TABLE_NAME = 'user_follow' AND INDEX_NAME = 'idx_user_follow_target'
            AND GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'follow_user_id,user_id')
        OR (TABLE_NAME = 'note' AND INDEX_NAME = 'idx_note_author_created'
            AND GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) = 'user_id,create_time,id')
    )
) supported_indexes;
DROP TEMPORARY TABLE community_schema_guard;

-- Only a database without notes has no legacy like identities to inspect.
INSERT INTO community_facts_cutover (id, ready, completed_at)
SELECT 1, NOT EXISTS(SELECT 1 FROM note),
       IF(NOT EXISTS(SELECT 1 FROM note), UTC_TIMESTAMP(3), NULL)
WHERE NOT EXISTS(SELECT 1 FROM community_facts_cutover WHERE id = 1);
