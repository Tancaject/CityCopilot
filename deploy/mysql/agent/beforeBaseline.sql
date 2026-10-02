-- Explicit adoption at version 1 requires the existing Ops schema; this callback never repairs it.
-- A missing preference table is created by V2; an existing one must already match its contract.
SELECT consumer_group,event_id,event_type,payload_hash,payload_json,status,quarantine_reason,processed_at FROM ops_event_inbox LIMIT 0;
SELECT id,incident_key,event_id,case_type,status,version,current_step,retry_count,cancellation_requested,lease_owner,lease_until,last_error,next_retry_at,open_incident_marker,created_at,updated_at,resolved_at FROM ops_case LIMIT 0;
SELECT id,case_id,evidence_type,source,payload_json,payload_hash,collected_at,expires_at FROM ops_evidence LIMIT 0;
SELECT id,case_id,plan_version,source,diagnosis_json,steps_json,plan_hash,status,created_by,created_at,superseded_at FROM ops_plan LIMIT 0;
SELECT id,case_id,plan_id,plan_hash,decision,decided_by,decided_at,expires_at,consumed_at,version FROM ops_approval LIMIT 0;
SELECT id,case_id,plan_id,step_index,tool_name,arguments_json,idempotency_key,status,result_json,error_code,started_at,completed_at FROM ops_execution LIMIT 0;
SELECT id,case_id,from_status,to_status,actor,reason,evidence_ids_json,created_at FROM ops_transition LIMIT 0;
SELECT id,source_type,source_id,payload_json,error_message,retry_count,created_at,replayed_at FROM ops_dead_letter LIMIT 0;

CREATE TEMPORARY TABLE agent_baseline_guard (
    ops_tables TINYINT NOT NULL DEFAULT 1 CONSTRAINT agent_baseline_ops_tables CHECK (ops_tables=1),
    unique_keys TINYINT NOT NULL DEFAULT 1 CONSTRAINT agent_baseline_unique_keys CHECK (unique_keys=1),
    foreign_keys TINYINT NOT NULL DEFAULT 1 CONSTRAINT agent_baseline_foreign_keys CHECK (foreign_keys=1),
    json_checks TINYINT NOT NULL DEFAULT 1 CONSTRAINT agent_baseline_json_checks CHECK (json_checks=1),
    incident_marker TINYINT NOT NULL DEFAULT 1 CONSTRAINT agent_baseline_incident_marker CHECK (incident_marker=1),
    preference_columns TINYINT NOT NULL DEFAULT 1 CONSTRAINT agent_baseline_preference_columns CHECK (preference_columns=1),
    preference_keys TINYINT NOT NULL DEFAULT 1 CONSTRAINT agent_baseline_preference_keys CHECK (preference_keys=1)
);

INSERT INTO agent_baseline_guard(ops_tables)
SELECT COUNT(*)=8 FROM information_schema.tables
WHERE table_schema=DATABASE() AND engine='InnoDB' AND table_collation LIKE 'utf8mb4%'
  AND table_name IN ('ops_event_inbox','ops_case','ops_evidence','ops_plan','ops_approval','ops_execution','ops_transition','ops_dead_letter');

INSERT INTO agent_baseline_guard(unique_keys)
SELECT COUNT(*)=14 FROM (
    SELECT table_name,index_name,GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns_in_key
    FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND non_unique=0
    GROUP BY table_name,index_name
    HAVING SUM(sub_part IS NOT NULL)=0
) actual
WHERE (table_name,index_name,columns_in_key) IN (
    ('ops_event_inbox','PRIMARY','consumer_group,event_id'),
    ('ops_case','PRIMARY','id'),
    ('ops_case','uk_ops_case_event','event_id'),
    ('ops_case','uk_ops_case_open_incident','incident_key,open_incident_marker'),
    ('ops_evidence','PRIMARY','id'),
    ('ops_plan','PRIMARY','id'),
    ('ops_plan','uk_ops_plan_case_version','case_id,plan_version'),
    ('ops_plan','uk_ops_plan_hash','plan_hash'),
    ('ops_approval','PRIMARY','id'),
    ('ops_approval','uk_ops_approval_plan','plan_id'),
    ('ops_execution','PRIMARY','id'),
    ('ops_execution','uk_ops_execution_idempotency','idempotency_key'),
    ('ops_transition','PRIMARY','id'),
    ('ops_dead_letter','PRIMARY','id')
);

INSERT INTO agent_baseline_guard(foreign_keys)
SELECT COUNT(*)=7 FROM (
    SELECT k.table_name,k.constraint_name,
           GROUP_CONCAT(k.column_name ORDER BY k.ordinal_position) AS child_columns,
           GROUP_CONCAT(CONCAT(k.referenced_table_name,'.',k.referenced_column_name) ORDER BY k.ordinal_position) AS parent_columns
    FROM information_schema.key_column_usage k
    JOIN information_schema.referential_constraints r
      ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name
    WHERE k.table_schema=DATABASE() AND k.referenced_table_schema=DATABASE()
      AND r.delete_rule IN ('RESTRICT','NO ACTION') AND r.update_rule IN ('RESTRICT','NO ACTION')
    GROUP BY k.table_name,k.constraint_name
) actual
WHERE (table_name,constraint_name,child_columns,parent_columns) IN (
    ('ops_evidence','fk_ops_evidence_case','case_id','ops_case.id'),
    ('ops_plan','fk_ops_plan_case','case_id','ops_case.id'),
    ('ops_approval','fk_ops_approval_case','case_id','ops_case.id'),
    ('ops_approval','fk_ops_approval_plan','plan_id','ops_plan.id'),
    ('ops_execution','fk_ops_execution_case','case_id','ops_case.id'),
    ('ops_execution','fk_ops_execution_plan','plan_id','ops_plan.id'),
    ('ops_transition','fk_ops_transition_case','case_id','ops_case.id')
);

INSERT INTO agent_baseline_guard(json_checks)
SELECT COUNT(*)=5
FROM information_schema.table_constraints tc
JOIN information_schema.check_constraints cc
  ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
WHERE tc.table_schema=DATABASE() AND tc.constraint_type='CHECK' AND tc.enforced='YES'
  AND (tc.table_name,tc.constraint_name,LOWER(REPLACE(REPLACE(cc.check_clause,'`',''),' ',''))) IN (
    ('ops_event_inbox','chk_ops_event_payload_json','json_valid(payload_json)'),
    ('ops_evidence','chk_ops_evidence_payload_json','json_valid(payload_json)'),
    ('ops_plan','chk_ops_plan_diagnosis_json','json_valid(diagnosis_json)'),
    ('ops_plan','chk_ops_plan_steps_json','json_valid(steps_json)'),
    ('ops_execution','chk_ops_execution_arguments_json','json_valid(arguments_json)')
);

INSERT INTO agent_baseline_guard(incident_marker)
SELECT COUNT(*)=1 FROM information_schema.columns
WHERE table_schema=DATABASE() AND table_name='ops_case' AND column_name='open_incident_marker'
  AND data_type='tinyint' AND extra='STORED GENERATED'
  -- MySQL metadata escapes quotes and preserves the DDL connection's character-set introducer.
  AND REGEXP_REPLACE(REGEXP_REPLACE(LOWER(REPLACE(generation_expression,CHAR(92),'')),'(?<=[(,]) *_[a-z0-9]+(?='')',''),'[[:space:]`()]+','')
      = 'casewhenstatusin''open'',''collecting_evidence'',''manual_review'',''plan_ready'',''awaiting_approval'',''approved'',''executing'',''verifying'',''retry_wait''then1elsenullend';

SET @agent_preference_exists=(SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema=DATABASE() AND table_name='agent_user_preference');

INSERT INTO agent_baseline_guard(preference_columns)
SELECT @agent_preference_exists=0 OR (
    (SELECT COUNT(*) FROM information_schema.tables
     WHERE table_schema=DATABASE() AND table_name='agent_user_preference'
       AND engine='InnoDB' AND table_collation LIKE 'utf8mb4%')=1
    AND (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='agent_user_preference' AND is_nullable='NO'
           AND ((column_name IN ('id','user_id') AND column_type='bigint unsigned')
             OR (column_name='preference_type' AND column_type='varchar(32)')
             OR (column_name='preference_key' AND column_type='char(64)')
             OR (column_name='preference_value' AND column_type='varchar(128)')
             OR (column_name='source' AND column_type='varchar(24)' AND column_default='EXPLICIT_CHAT')
             OR (column_name='mention_count' AND column_type='int unsigned' AND column_default='1')
             OR (column_name IN ('created_at','updated_at') AND column_type='timestamp(3)'
                 AND LOWER(column_default)='current_timestamp(3)')))=9
    AND (SELECT COUNT(*) FROM information_schema.columns
         WHERE table_schema=DATABASE() AND table_name='agent_user_preference'
           AND ((column_name='id' AND extra LIKE '%auto_increment%')
             OR (column_name='updated_at' AND LOWER(extra) LIKE '%on update current_timestamp(3)%')))=2
);

INSERT INTO agent_baseline_guard(preference_keys)
SELECT @agent_preference_exists=0 OR COUNT(*)=2 FROM (
    SELECT index_name,GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns_in_key
    FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='agent_user_preference' AND non_unique=0
    GROUP BY index_name
    HAVING SUM(sub_part IS NOT NULL)=0
) actual
WHERE (index_name,columns_in_key) IN (
    ('PRIMARY','id'),
    ('uk_agent_user_preference','user_id,preference_type,preference_key')
);

DROP TEMPORARY TABLE agent_baseline_guard;
