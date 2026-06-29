ALTER TABLE public.super_agent_raptor_embedding
    ADD COLUMN IF NOT EXISTS scope_type VARCHAR(32) NOT NULL DEFAULT 'DOCUMENT',
    ADD COLUMN IF NOT EXISTS scope_key VARCHAR(255) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS source_document_ids_json TEXT,
    ADD COLUMN IF NOT EXISTS source_task_ids_json TEXT;

COMMENT ON COLUMN public.super_agent_raptor_embedding.scope_type IS 'RAPTOR摘要范围类型：DOCUMENT/DATASET';
COMMENT ON COLUMN public.super_agent_raptor_embedding.scope_key IS 'RAPTOR摘要范围键，例如 document:{id} / knowledge:{code} / global';
COMMENT ON COLUMN public.super_agent_raptor_embedding.source_document_ids_json IS '跨文档摘要覆盖的文档id JSON数组';
COMMENT ON COLUMN public.super_agent_raptor_embedding.source_task_ids_json IS '跨文档摘要覆盖的索引任务id JSON数组';

CREATE INDEX IF NOT EXISTS idx_super_agent_raptor_embedding_scope
    ON public.super_agent_raptor_embedding (scope_type, scope_key);

UPDATE public.super_agent_raptor_embedding
SET scope_key = CONCAT('document:', document_id)
WHERE (scope_key IS NULL OR scope_key = '')
  AND document_id IS NOT NULL;
