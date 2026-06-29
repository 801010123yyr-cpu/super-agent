ALTER TABLE `super_agent_raptor_node`
    ADD COLUMN `scope_type` varchar(32) NOT NULL DEFAULT 'DOCUMENT' COMMENT 'RAPTOR摘要范围类型：DOCUMENT/DATASET' AFTER `task_id`,
    ADD COLUMN `scope_key` varchar(255) NOT NULL DEFAULT '' COMMENT 'RAPTOR摘要范围键，例如 document:{id} / knowledge:{code} / global' AFTER `scope_type`,
    ADD COLUMN `source_document_ids_json` text COMMENT '跨文档摘要覆盖的文档id JSON数组' AFTER `source_parent_block_ids_json`,
    ADD COLUMN `source_task_ids_json` text COMMENT '跨文档摘要覆盖的索引任务id JSON数组' AFTER `source_document_ids_json`,
    ADD KEY `idx_raptor_node_scope` (`scope_type`, `scope_key`);

UPDATE `super_agent_raptor_node`
SET `scope_key` = CONCAT('document:', `document_id`)
WHERE (`scope_key` IS NULL OR `scope_key` = '')
  AND `document_id` IS NOT NULL;
