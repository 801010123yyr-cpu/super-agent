/*
  O2-O8 RAG 完整测试：按当前 15 份文档物理清理旧 PG 向量数据

  用途：
  - MySQL 已经删除旧数据并重新上传了 15 份测试文档。
  - PostgreSQL 的 public.super_agent_document_embedding 和 public.super_agent_raptor_embedding
    还残留旧 document_id 的向量数据。
  - 本脚本只保留下面 15 个 document_id 对应的数据，其他 document_id 的数据全部物理删除。

  文档 ID 来源：
  - 已按用户提供的 MySQL 查询从 super_agent_document 获取。
  - 查询条件为 status = 1 且 original_file_name in 15 份必传样例。

  执行方式：
    PGPASSWORD=postgres psql -h 127.0.0.1 -p 5432 -U postgres -d super_agent_pgvector \
      -f sql/PostgresSql/O2-O8-RAG完整测试清理旧PG向量数据.sql

  注意：
  - 本脚本只操作 PostgreSQL，不改 MySQL。
  - 本脚本是物理 DELETE，不是软删除。
  - super_agent_raptor_embedding 中 document_id = 0 的 dataset-level 旧数据也会被删除；
    因为它不属于这 15 个 document_id。
*/

BEGIN;

CREATE TEMP TABLE keep_document_ids (
    document_id BIGINT PRIMARY KEY,
    file_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO keep_document_ids (document_id, file_name) VALUES
(2296737919064432640, 'O2-provider-artifact验收样例.pdf'),
(2296737919064433640, 'O2-扫描OCR验收样例-图片型PDF.pdf'),
(2296737919064434525, 'O2-扫描OCR验收样例-文字截图.png'),
(2296738159582601310, 'O6多社区排序-客户数据访问控制别名B.md'),
(2296738125222865466, 'O6多社区排序-客户数据访问控制规范A.md'),
(2296738090863129667, 'O6多社区排序-生产发布回滚别名B.md'),
(2296738056503393982, 'O6多社区排序-生产发布回滚规范A.md'),
(2296738056503388553, 'O6跨文档图谱-审计系统别名说明B.md'),
(2296738022143652966, 'O6跨文档图谱-审计证据规范A.md'),
(2296737953424176918, '客户数据分级与访问控制管理制度.md'),
(2296737987783912016, '差旅与费用报销管理办法.md'),
(2296737919064435493, '星联智服全渠道客服平台上线与运营管理手册.md'),
(2296737953424172808, '核心业务系统故障应急响应预案.md'),
(2296737987783916947, '澄星智能新员工入职培训手册.md'),
(2296737919064439185, '生产环境发布与回滚操作规范.md');

/* =========================================================
   1. 执行前预览
   ========================================================= */

SELECT
    'keep_document_ids' AS item,
    COUNT(*) AS count
FROM keep_document_ids;

SELECT
    'super_agent_document_embedding_before' AS item,
    COUNT(*) AS total_rows,
    COUNT(*) FILTER (WHERE document_id IN (SELECT document_id FROM keep_document_ids)) AS keep_rows,
    COUNT(*) FILTER (WHERE document_id NOT IN (SELECT document_id FROM keep_document_ids)) AS delete_rows
FROM public.super_agent_document_embedding;

SELECT
    'super_agent_raptor_embedding_before' AS item,
    COUNT(*) AS total_rows,
    COUNT(*) FILTER (WHERE document_id IN (SELECT document_id FROM keep_document_ids)) AS keep_rows,
    COUNT(*) FILTER (WHERE document_id NOT IN (SELECT document_id FROM keep_document_ids)) AS delete_rows
FROM public.super_agent_raptor_embedding;

SELECT
    'document_embedding_delete_by_document_id' AS item,
    document_id,
    COUNT(*) AS rows_to_delete
FROM public.super_agent_document_embedding
WHERE document_id NOT IN (SELECT document_id FROM keep_document_ids)
GROUP BY document_id
ORDER BY document_id;

SELECT
    'raptor_embedding_delete_by_document_id' AS item,
    document_id,
    COUNT(*) AS rows_to_delete
FROM public.super_agent_raptor_embedding
WHERE document_id NOT IN (SELECT document_id FROM keep_document_ids)
GROUP BY document_id
ORDER BY document_id;

/* =========================================================
   2. 物理删除旧数据
   ========================================================= */

DELETE FROM public.super_agent_document_embedding
WHERE document_id NOT IN (SELECT document_id FROM keep_document_ids);

DELETE FROM public.super_agent_raptor_embedding
WHERE document_id NOT IN (SELECT document_id FROM keep_document_ids);

/* =========================================================
   3. 删除后复核
   ========================================================= */

SELECT
    'super_agent_document_embedding_after' AS item,
    COUNT(*) AS total_rows,
    COUNT(*) FILTER (WHERE document_id IN (SELECT document_id FROM keep_document_ids)) AS keep_rows,
    COUNT(*) FILTER (WHERE document_id NOT IN (SELECT document_id FROM keep_document_ids)) AS old_rows_remaining
FROM public.super_agent_document_embedding;

SELECT
    'super_agent_raptor_embedding_after' AS item,
    COUNT(*) AS total_rows,
    COUNT(*) FILTER (WHERE document_id IN (SELECT document_id FROM keep_document_ids)) AS keep_rows,
    COUNT(*) FILTER (WHERE document_id NOT IN (SELECT document_id FROM keep_document_ids)) AS old_rows_remaining
FROM public.super_agent_raptor_embedding;

SELECT
    'document_embedding_keep_by_document_id' AS item,
    e.document_id,
    k.file_name,
    COUNT(*) AS rows_kept
FROM public.super_agent_document_embedding e
JOIN keep_document_ids k ON k.document_id = e.document_id
GROUP BY e.document_id, k.file_name
ORDER BY k.file_name;

SELECT
    'raptor_embedding_keep_by_document_id' AS item,
    e.document_id,
    k.file_name,
    COUNT(*) AS rows_kept
FROM public.super_agent_raptor_embedding e
JOIN keep_document_ids k ON k.document_id = e.document_id
GROUP BY e.document_id, k.file_name
ORDER BY k.file_name;

COMMIT;

/*
  可选：删除后如果要让备份文件更小，建议执行 VACUUM。

  普通回收统计：
    VACUUM (ANALYZE) public.super_agent_document_embedding;
    VACUUM (ANALYZE) public.super_agent_raptor_embedding;

  强制压缩物理文件，需低峰期执行，会锁表：
    VACUUM FULL public.super_agent_document_embedding;
    VACUUM FULL public.super_agent_raptor_embedding;
*/
