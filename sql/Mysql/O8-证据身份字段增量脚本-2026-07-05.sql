-- O8 证据身份原则修复：为现有 super_agent_chat_retrieval_result 表补齐观测字段。
-- 适用场景：已存在旧表结构时执行；全新初始化可直接使用 create_table_mysql.sql。

ALTER TABLE super_agent_chat_retrieval_result
    ADD COLUMN chunk_type VARCHAR(32) DEFAULT NULL COMMENT '切块类型：TEXT/LIST/TABLE/TITLE/RAPTOR_SOURCE_CHUNK等' AFTER chunk_id,
    ADD COLUMN context_identity VARCHAR(255) DEFAULT NULL COMMENT '上下文身份：ParentBlock、GraphRAG包装、RAPTOR摘要等' AFTER chunk_char_count,
    ADD COLUMN citation_identity VARCHAR(255) DEFAULT NULL COMMENT '真实可引用证据身份：chunk/quote/table cell/source chunk' AFTER context_identity,
    ADD COLUMN citation_evidence_type VARCHAR(64) DEFAULT NULL COMMENT '引用证据类型：CHUNK/TABLE_CELL_OR_ROW/KG_QUOTE_SOURCE/RAPTOR_SOURCE_CHUNK/CONTEXT_ONLY' AFTER citation_identity,
    ADD COLUMN context_only TINYINT(1) DEFAULT '0' COMMENT '是否仅为上下文，不可直接作为citation证据' AFTER citation_evidence_type,
    ADD COLUMN source_evidence_resolved TINYINT(1) DEFAULT '0' COMMENT '是否已解析到真实可引用source evidence' AFTER context_only;

CREATE INDEX idx_retrieval_result_citation_identity
    ON super_agent_chat_retrieval_result (citation_identity);

CREATE INDEX idx_retrieval_result_context_only
    ON super_agent_chat_retrieval_result (context_only, source_evidence_resolved);
