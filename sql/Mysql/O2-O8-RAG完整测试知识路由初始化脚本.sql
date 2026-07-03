/*
  O2-O8 RAG 完整测试知识路由初始化脚本

  使用顺序：
  1. 先在管理端一次性上传 document/O2-O8-RAG能力完整测试验收方案.md 第 4.1 节列出的 15 份必传样例。
  2. 每份文档完成“确认策略方案”和“构建索引执行”，确认 parse_status=3、strategy_status=3、index_status=3。
  3. 直接执行本脚本。脚本会按 original_file_name 自动选择每个文件最新上传的有效文档记录。

  注意：
  - 本脚本只初始化测试知识域、知识主题、文档画像和主题文档关联。
  - 本脚本会更新 super_agent_document 的 knowledge_scope_code、knowledge_scope_name、business_category、document_tags。
  - 本脚本使用 INSERT ... ON DUPLICATE KEY UPDATE，可重复执行。
  - 本脚本不会修改文档解析、策略方案、索引状态、chunk、向量库、ES/BM25、KG 或 RAPTOR 数据。
  - 如需重新跑完整验收，优先重新上传/重新解析文档后重跑本脚本；不需要手动填写文档 ID，也不需要删除不同批次文档。
*/

START TRANSACTION;

/* =========================================================
   0. 自动按文件名选择最新文档 ID
   ========================================================= */

SET @file_o2_provider_pdf = 'O2-provider-artifact验收样例.pdf';
SET @file_o2_ocr_pdf = 'O2-扫描OCR验收样例-图片型PDF.pdf';
SET @file_o2_ocr_png = 'O2-扫描OCR验收样例-文字截图.png';

SET @file_xinglian = '星联智服全渠道客服平台上线与运营管理手册.md';
SET @file_release = '生产环境发布与回滚操作规范.md';
SET @file_incident = '核心业务系统故障应急响应预案.md';
SET @file_data_policy = '客户数据分级与访问控制管理制度.md';
SET @file_travel = '差旅与费用报销管理办法.md';
SET @file_onboarding = '澄星智能新员工入职培训手册.md';

SET @file_audit_evidence = 'O6跨文档图谱-审计证据规范A.md';
SET @file_audit_alias = 'O6跨文档图谱-审计系统别名说明B.md';
SET @file_release_graph_spec = 'O6多社区排序-生产发布回滚规范A.md';
SET @file_release_graph_alias = 'O6多社区排序-生产发布回滚别名B.md';
SET @file_data_graph_spec = 'O6多社区排序-客户数据访问控制规范A.md';
SET @file_data_graph_alias = 'O6多社区排序-客户数据访问控制别名B.md';

SET @doc_o2_provider_pdf_id = (SELECT id FROM super_agent_document WHERE status = 1 AND original_file_name = @file_o2_provider_pdf ORDER BY create_time DESC, id DESC LIMIT 1);
SET @doc_o2_ocr_pdf_id = (SELECT id FROM super_agent_document WHERE status = 1 AND original_file_name = @file_o2_ocr_pdf ORDER BY create_time DESC, id DESC LIMIT 1);
SET @doc_o2_ocr_png_id = (SELECT id FROM super_agent_document WHERE status = 1 AND original_file_name = @file_o2_ocr_png ORDER BY create_time DESC, id DESC LIMIT 1);

SET @doc_xinglian_id = (SELECT id FROM super_agent_document WHERE status = 1 AND original_file_name = @file_xinglian ORDER BY create_time DESC, id DESC LIMIT 1);
SET @doc_release_id = (SELECT id FROM super_agent_document WHERE status = 1 AND original_file_name = @file_release ORDER BY create_time DESC, id DESC LIMIT 1);
SET @doc_incident_id = (SELECT id FROM super_agent_document WHERE status = 1 AND original_file_name = @file_incident ORDER BY create_time DESC, id DESC LIMIT 1);
SET @doc_data_policy_id = (SELECT id FROM super_agent_document WHERE status = 1 AND original_file_name = @file_data_policy ORDER BY create_time DESC, id DESC LIMIT 1);
SET @doc_travel_id = (SELECT id FROM super_agent_document WHERE status = 1 AND original_file_name = @file_travel ORDER BY create_time DESC, id DESC LIMIT 1);
SET @doc_onboarding_id = (SELECT id FROM super_agent_document WHERE status = 1 AND original_file_name = @file_onboarding ORDER BY create_time DESC, id DESC LIMIT 1);

SET @doc_audit_evidence_id = (SELECT id FROM super_agent_document WHERE status = 1 AND original_file_name = @file_audit_evidence ORDER BY create_time DESC, id DESC LIMIT 1);
SET @doc_audit_alias_id = (SELECT id FROM super_agent_document WHERE status = 1 AND original_file_name = @file_audit_alias ORDER BY create_time DESC, id DESC LIMIT 1);
SET @doc_release_graph_spec_id = (SELECT id FROM super_agent_document WHERE status = 1 AND original_file_name = @file_release_graph_spec ORDER BY create_time DESC, id DESC LIMIT 1);
SET @doc_release_graph_alias_id = (SELECT id FROM super_agent_document WHERE status = 1 AND original_file_name = @file_release_graph_alias ORDER BY create_time DESC, id DESC LIMIT 1);
SET @doc_data_graph_spec_id = (SELECT id FROM super_agent_document WHERE status = 1 AND original_file_name = @file_data_graph_spec ORDER BY create_time DESC, id DESC LIMIT 1);
SET @doc_data_graph_alias_id = (SELECT id FROM super_agent_document WHERE status = 1 AND original_file_name = @file_data_graph_alias ORDER BY create_time DESC, id DESC LIMIT 1);

SELECT id, document_name, original_file_name, parse_status, strategy_status, index_status, last_index_task_id, create_time
FROM super_agent_document
WHERE id IN (
    @doc_o2_provider_pdf_id,
    @doc_o2_ocr_pdf_id,
    @doc_o2_ocr_png_id,
    @doc_xinglian_id,
    @doc_release_id,
    @doc_incident_id,
    @doc_data_policy_id,
    @doc_travel_id,
    @doc_onboarding_id,
    @doc_audit_evidence_id,
    @doc_audit_alias_id,
    @doc_release_graph_spec_id,
    @doc_release_graph_alias_id,
    @doc_data_graph_spec_id,
    @doc_data_graph_alias_id
)
ORDER BY original_file_name, create_time DESC;

/* =========================================================
   1. 固定配置编码
   ========================================================= */

SET @scope_parse_code = 'rag_o_parse';
SET @scope_parse_name = 'O2 解析固定回归';
SET @scope_operation_code = 'rag_o_operation';
SET @scope_operation_name = '运营制度与RAG问答评测';
SET @scope_graph_code = 'rag_o_graph';
SET @scope_graph_name = 'GraphRAG跨文档图谱评测';

/*
  这些 id 只用于新插入范围、主题、关系、画像时。
  如果你的数据库里极端情况下已经占用了这些 id，可以把 @base_id 改成其他未使用的大整数。
*/
SET @base_id = 8800041700000000000;

/* =========================================================
   1.1 自动取数和状态保护
   ========================================================= */

SET @missing_doc_ids = CONCAT_WS(',',
    IF(@doc_o2_provider_pdf_id IS NULL, @file_o2_provider_pdf, NULL),
    IF(@doc_o2_ocr_pdf_id IS NULL, @file_o2_ocr_pdf, NULL),
    IF(@doc_o2_ocr_png_id IS NULL, @file_o2_ocr_png, NULL),
    IF(@doc_xinglian_id IS NULL, @file_xinglian, NULL),
    IF(@doc_release_id IS NULL, @file_release, NULL),
    IF(@doc_incident_id IS NULL, @file_incident, NULL),
    IF(@doc_data_policy_id IS NULL, @file_data_policy, NULL),
    IF(@doc_travel_id IS NULL, @file_travel, NULL),
    IF(@doc_onboarding_id IS NULL, @file_onboarding, NULL),
    IF(@doc_audit_evidence_id IS NULL, @file_audit_evidence, NULL),
    IF(@doc_audit_alias_id IS NULL, @file_audit_alias, NULL),
    IF(@doc_release_graph_spec_id IS NULL, @file_release_graph_spec, NULL),
    IF(@doc_release_graph_alias_id IS NULL, @file_release_graph_alias, NULL),
    IF(@doc_data_graph_spec_id IS NULL, @file_data_graph_spec, NULL),
    IF(@doc_data_graph_alias_id IS NULL, @file_data_graph_alias, NULL)
);

SET @missing_doc_ids_error = IF(
    @missing_doc_ids IS NULL OR @missing_doc_ids = '',
    NULL,
    CONCAT('这些文件没有找到 status=1 的最新上传记录，请先上传后再执行脚本: ', @missing_doc_ids)
);

SELECT
    CASE
        WHEN @missing_doc_ids_error IS NULL THEN 'OK: 已自动找到 15 个文档 ID'
        ELSE @missing_doc_ids_error
    END AS parameter_check;

SET @missing_doc_ids_sql = IF(
    @missing_doc_ids_error IS NULL,
    'SELECT 1',
    CONCAT('SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''', @missing_doc_ids_error, '''')
);
PREPARE missing_doc_ids_stmt FROM @missing_doc_ids_sql;
EXECUTE missing_doc_ids_stmt;
DEALLOCATE PREPARE missing_doc_ids_stmt;

SET @not_ready_docs = (
    SELECT GROUP_CONCAT(CONCAT(
        original_file_name,
        '(parse=', IFNULL(CAST(parse_status AS CHAR), 'NULL'),
        ', strategy=', IFNULL(CAST(strategy_status AS CHAR), 'NULL'),
        ', index=', IFNULL(CAST(index_status AS CHAR), 'NULL'),
        ')'
    ) ORDER BY original_file_name SEPARATOR '; ')
    FROM super_agent_document
    WHERE id IN (
        @doc_o2_provider_pdf_id,
        @doc_o2_ocr_pdf_id,
        @doc_o2_ocr_png_id,
        @doc_xinglian_id,
        @doc_release_id,
        @doc_incident_id,
        @doc_data_policy_id,
        @doc_travel_id,
        @doc_onboarding_id,
        @doc_audit_evidence_id,
        @doc_audit_alias_id,
        @doc_release_graph_spec_id,
        @doc_release_graph_alias_id,
        @doc_data_graph_spec_id,
        @doc_data_graph_alias_id
    )
      AND (IFNULL(parse_status, -1) <> 3 OR IFNULL(strategy_status, -1) <> 3 OR IFNULL(index_status, -1) <> 3)
);

SET @not_ready_docs_error = IF(
    @not_ready_docs IS NULL OR @not_ready_docs = '',
    NULL,
    CONCAT('以下最新文档还没有完成 parse_status=3、strategy_status=3、index_status=3，请等待完成后重跑: ', @not_ready_docs)
);

SELECT
    CASE
        WHEN @not_ready_docs_error IS NULL THEN 'OK: 15 个最新文档均已完成解析、策略确认和索引构建'
        ELSE @not_ready_docs_error
    END AS document_status_check;

SET @not_ready_docs_sql = IF(
    @not_ready_docs_error IS NULL,
    'SELECT 1',
    CONCAT('SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''', @not_ready_docs_error, '''')
);
PREPARE not_ready_docs_stmt FROM @not_ready_docs_sql;
EXECUTE not_ready_docs_stmt;
DEALLOCATE PREPARE not_ready_docs_stmt;

/* =========================================================
   2. 更新 15 份文档主表元数据
   ========================================================= */

UPDATE super_agent_document
SET document_name = 'O2-provider-artifact验收样例',
    knowledge_scope_code = @scope_parse_code,
    knowledge_scope_name = @scope_parse_name,
    business_category = 'O2解析固定样例',
    document_tags = 'O2,Document Mind,layout,表格,FIGURE,bbox,artifact,解析回归',
    edit_time = NOW()
WHERE id = @doc_o2_provider_pdf_id AND status = 1;

UPDATE super_agent_document
SET document_name = 'O2-扫描OCR验收样例-图片型PDF',
    knowledge_scope_code = @scope_parse_code,
    knowledge_scope_name = @scope_parse_name,
    business_category = 'O2解析固定样例',
    document_tags = 'O2,OCR,图片型PDF,PAGE_IMAGE,TABLE_IMAGE,bbox,解析回归',
    edit_time = NOW()
WHERE id = @doc_o2_ocr_pdf_id AND status = 1;

UPDATE super_agent_document
SET document_name = 'O2-扫描OCR验收样例-文字截图',
    knowledge_scope_code = @scope_parse_code,
    knowledge_scope_name = @scope_parse_name,
    business_category = 'O2解析固定样例',
    document_tags = 'O2,OCR,PNG,图片文本,蓝桥订单,RAG-O2-20260630,支付回调延迟',
    edit_time = NOW()
WHERE id = @doc_o2_ocr_png_id AND status = 1;

UPDATE super_agent_document
SET document_name = '星联智服全渠道客服平台上线与运营管理手册',
    knowledge_scope_code = @scope_operation_code,
    knowledge_scope_name = @scope_operation_name,
    business_category = '客服平台运营手册',
    document_tags = '星联智服,客服平台,上线运营,知识治理,机器人策略,灰度验证,上线观察,故障处理,RAG,O8基线',
    edit_time = NOW()
WHERE id = @doc_xinglian_id AND status = 1;

UPDATE super_agent_document
SET document_name = '生产环境发布与回滚操作规范',
    knowledge_scope_code = @scope_operation_code,
    knowledge_scope_name = @scope_operation_name,
    business_category = '生产发布规范',
    document_tags = '生产发布,回滚,灰度节奏,发布暂停,NovaRAG,召回成功率,强制回滚,O8路由,O7跨文档总结',
    edit_time = NOW()
WHERE id = @doc_release_id AND status = 1;

UPDATE super_agent_document
SET document_name = '核心业务系统故障应急响应预案',
    knowledge_scope_code = @scope_operation_code,
    knowledge_scope_name = @scope_operation_name,
    business_category = '故障应急预案',
    document_tags = '故障应急,NovaRAG,检索服务降级,P1,P2,人工转接,多文档路由,O3 rerank',
    edit_time = NOW()
WHERE id = @doc_incident_id AND status = 1;

UPDATE super_agent_document
SET document_name = '客户数据分级与访问控制管理制度',
    knowledge_scope_code = @scope_operation_code,
    knowledge_scope_name = @scope_operation_name,
    business_category = '数据访问制度',
    document_tags = '客户数据,L4高敏感,访问控制,审批,日志保存,DataCleanRoom,表格问答,citation',
    edit_time = NOW()
WHERE id = @doc_data_policy_id AND status = 1;

UPDATE super_agent_document
SET document_name = '差旅与费用报销管理办法',
    knowledge_scope_code = @scope_operation_code,
    knowledge_scope_name = @scope_operation_name,
    business_category = '费用报销制度',
    document_tags = '差旅,费用报销,住宿标准,审批金额阈值,表格问答',
    edit_time = NOW()
WHERE id = @doc_travel_id AND status = 1;

UPDATE super_agent_document
SET document_name = '澄星智能新员工入职培训手册',
    knowledge_scope_code = @scope_operation_code,
    knowledge_scope_name = @scope_operation_name,
    business_category = '入职培训手册',
    document_tags = '入职培训,首周日程,30天,60天,90天,表格问答',
    edit_time = NOW()
WHERE id = @doc_onboarding_id AND status = 1;

UPDATE super_agent_document
SET document_name = 'O6跨文档图谱-审计证据规范A',
    knowledge_scope_code = @scope_graph_code,
    knowledge_scope_name = @scope_graph_name,
    business_category = 'O6 GraphRAG样例',
    document_tags = 'O6,GraphRAG,AuditTrail,审计系统,权限记录,跨文档canonical,关系证据',
    edit_time = NOW()
WHERE id = @doc_audit_evidence_id AND status = 1;

UPDATE super_agent_document
SET document_name = 'O6跨文档图谱-审计系统别名说明B',
    knowledge_scope_code = @scope_graph_code,
    knowledge_scope_name = @scope_graph_name,
    business_category = 'O6 GraphRAG样例',
    document_tags = 'O6,GraphRAG,AuditTrail,审计系统,别名,系统职责,负边界',
    edit_time = NOW()
WHERE id = @doc_audit_alias_id AND status = 1;

UPDATE super_agent_document
SET document_name = 'O6多社区排序-生产发布回滚规范A',
    knowledge_scope_code = @scope_graph_code,
    knowledge_scope_name = @scope_graph_name,
    business_category = 'O6 GraphRAG样例',
    document_tags = 'O6,GraphRAG,ReleaseControl,CAB,值班SRE,生产发布,回滚演练,community',
    edit_time = NOW()
WHERE id = @doc_release_graph_spec_id AND status = 1;

UPDATE super_agent_document
SET document_name = 'O6多社区排序-生产发布回滚别名B',
    knowledge_scope_code = @scope_graph_code,
    knowledge_scope_name = @scope_graph_name,
    business_category = 'O6 GraphRAG样例',
    document_tags = 'O6,GraphRAG,ReleaseControl,生产发布控制台,变更评审委员会,别名,community',
    edit_time = NOW()
WHERE id = @doc_release_graph_alias_id AND status = 1;

UPDATE super_agent_document
SET document_name = 'O6多社区排序-客户数据访问控制规范A',
    knowledge_scope_code = @scope_graph_code,
    knowledge_scope_name = @scope_graph_name,
    business_category = 'O6 GraphRAG样例',
    document_tags = 'O6,GraphRAG,DataAccessGuard,客户数据访问控制,数据治理负责人,信息安全部,community',
    edit_time = NOW()
WHERE id = @doc_data_graph_spec_id AND status = 1;

UPDATE super_agent_document
SET document_name = 'O6多社区排序-客户数据访问控制别名B',
    knowledge_scope_code = @scope_graph_code,
    knowledge_scope_name = @scope_graph_name,
    business_category = 'O6 GraphRAG样例',
    document_tags = 'O6,GraphRAG,DataAccessGuard,客户数据Owner,安全复核组,别名,负边界',
    edit_time = NOW()
WHERE id = @doc_data_graph_alias_id AND status = 1;

/* =========================================================
   3. 知识范围配置
   ========================================================= */

INSERT INTO super_agent_knowledge_scope_node (
    id, scope_code, scope_name, parent_scope_code, description, aliases, examples, sort_order,
    create_time, edit_time, status
)
VALUES
(
    @base_id + 1,
    @scope_parse_code,
    @scope_parse_name,
    NULL,
    '用于承接 O2 固定解析回归样例，只验证 OCR、layout、reading order、表格、bbox、artifact 和 O9 文档侧观测，不作为生产业务问答知识域。',
    'O2解析,OCR回归,Document Mind回归,解析固定样例,文档侧观测',
    '["O2 扫描 OCR 样例里有没有识别到关键短语","O2 固定样例中的表格有几行几列","这份 PDF 样例是否包含图示或图片区域"]',
    10,
    NOW(), NOW(), 1
),
(
    @base_id + 2,
    @scope_operation_code,
    @scope_operation_name,
    NULL,
    '用于承接运营制度、客服平台上线、发布回滚、故障应急、数据访问、差旅报销和入职培训类问答，重点验收 O3/O4/O7/O8。',
    '运营制度,RAG问答评测,客服平台,生产发布,故障应急,客户数据,差旅报销,入职培训',
    '["检索命中率突然下降的可能原因都有哪些","NovaRAG 检索服务降级时按什么顺序处理","L4 高敏感信息的审批要求是什么"]',
    20,
    NOW(), NOW(), 1
),
(
    @base_id + 3,
    @scope_graph_code,
    @scope_graph_name,
    NULL,
    '用于承接 O6 GraphRAG 跨文档别名、canonical、实体关系、community、多社区排序和负边界测试。',
    'O6图谱,GraphRAG,跨文档图谱,AuditTrail,ReleaseControl,DataAccessGuard,多社区排序',
    '["审计系统有哪些权限相关要求","ReleaseControl 和 CAB 是什么关系","DataAccessGuard 和信息安全部是什么关系"]',
    30,
    NOW(), NOW(), 1
)
ON DUPLICATE KEY UPDATE
    scope_name = VALUES(scope_name),
    parent_scope_code = VALUES(parent_scope_code),
    description = VALUES(description),
    aliases = VALUES(aliases),
    examples = VALUES(examples),
    sort_order = VALUES(sort_order),
    edit_time = NOW(),
    status = 1;

/* =========================================================
   4. 知识主题配置
   answer_shape 固定使用 explain/list/steps/compare/structure
   execution_preference 固定使用 retrieval/graph_assist/graph_then_evidence
   ========================================================= */

INSERT INTO super_agent_knowledge_topic_node (
    id, topic_code, topic_name, scope_code, description, aliases, examples,
    answer_shape, execution_preference, sort_order,
    create_time, edit_time, status
)
VALUES
(@base_id + 101, 'o2_parse_artifact', 'O2 Document Mind 解析产物', @scope_parse_code, '验证普通 PDF 的 layout、表格、FIGURE block、artifact、bbox 和 RAG 产物联动。', 'Document Mind,layout,artifact,bbox,FIGURE,表格解析', '["O2 固定样例中的表格能否被识别成结构化表格","这份 PDF 样例是否包含图示或图片区域"]', 'structure', 'retrieval', 10, NOW(), NOW(), 1),
(@base_id + 102, 'o2_ocr_pdf', 'O2 图片型 PDF OCR', @scope_parse_code, '验证图片型 PDF 的 OCR 文本、页面图片、表格图片、bbox overlay 和关键业务短语。', '图片型PDF,OCR,PAGE_IMAGE,TABLE_IMAGE,扫描件', '["图片型 PDF OCR 是否识别到了编号条款和业务关键词"]', 'explain', 'retrieval', 20, NOW(), NOW(), 1),
(@base_id + 103, 'o2_ocr_png', 'O2 图片 OCR', @scope_parse_code, '验证 PNG 图片文件进入解析主链路并识别关键短语和图片表格。', 'PNG OCR,文字截图,蓝桥订单,RAG-O2-20260630,支付回调延迟', '["O2 扫描 OCR 样例里有没有识别到蓝桥订单 7391"]', 'explain', 'retrieval', 30, NOW(), NOW(), 1),

(@base_id + 201, 'operation_service_go_live', '客服平台上线与运营', @scope_operation_code, '回答星联智服客服平台上线、知识治理、机器人策略、灰度验证、上线观察、故障处理和质量评估问题。', '星联智服,客服平台,上线运营,知识治理,机器人策略,观察时长,检索命中率,人工转接率', '["检索命中率突然下降的可能原因都有哪些","人工转接率异常升高检查顺序是什么","上线观察与值班规则中观察时长有哪些"]', 'steps', 'retrieval', 10, NOW(), NOW(), 1),
(@base_id + 202, 'operation_release_rollback', '生产发布与回滚', @scope_operation_code, '回答生产发布、灰度节奏、发布暂停、强制回滚、NovaRAG 召回成功率和发布风险控制问题。', '生产发布,回滚,灰度节奏,发布暂停,强制回滚,NovaRAG,召回成功率', '["生产发布默认灰度节奏分几个阶段","强制回滚条件有哪些","哪些情况下默认动作是暂停发布"]', 'steps', 'retrieval', 20, NOW(), NOW(), 1),
(@base_id + 203, 'operation_incident_response', '故障应急响应', @scope_operation_code, '回答核心业务系统故障分级、NovaRAG 检索服务降级、应急处理顺序和升级边界。', '故障应急,NovaRAG降级,检索服务降级,P1,P2,人工转接激增', '["NovaRAG 检索服务降级时按什么顺序处理","连续 15 分钟无法返回检索结果故障等级怎么判断"]', 'steps', 'retrieval', 30, NOW(), NOW(), 1),
(@base_id + 204, 'operation_data_access', '客户数据访问控制', @scope_operation_code, '回答客户数据分级、L4 高敏感数据访问、审批、导出限制、日志保存和审计要求。', '客户数据,L4高敏感,访问控制,审批,DataCleanRoom,日志保存,表格问答', '["L4 高敏感信息的审批要求和默认有效期是什么","L3 和 L4 数据的日志保存期限分别是多少"]', 'list', 'retrieval', 40, NOW(), NOW(), 1),
(@base_id + 205, 'operation_travel_reimbursement', '差旅与费用报销', @scope_operation_code, '回答差旅住宿标准、报销金额阈值、审批流程和费用合规问题。', '差旅,费用报销,住宿标准,审批阈值,财务BP', '["北京出差酒店住宿上限是多少","10000 元以上报销需要哪些审批"]', 'list', 'retrieval', 50, NOW(), NOW(), 1),
(@base_id + 206, 'operation_onboarding_training', '新员工入职培训', @scope_operation_code, '回答入职培训日程、首周安排、30/60/90 天关注重点和培训模块。', '入职培训,首周日程,30天,60天,90天,培训模块', '["入职当天 09:30-10:30 的培训模块是什么","第 30 天、第 60 天、第 90 天分别关注什么"]', 'list', 'retrieval', 60, NOW(), NOW(), 1),
(@base_id + 207, 'operation_raptor_summary', '运营制度跨文档总结', @scope_operation_code, '用于 O7 RAPTOR 单文档和跨文档总结测试，聚合客服平台上线、生产发布和故障应急主线。', 'RAPTOR,跨文档总结,上线风险控制,灰度验证,回滚评估,质量复盘', '["请总结星联智服平台从灰度上线到生产发布再到质量复盘的完整治理流程","这两份规范中和上线风险控制相关的要求有哪些"]', 'compare', 'retrieval', 70, NOW(), NOW(), 1),

(@base_id + 301, 'graph_audit_trail', 'AuditTrail 审计权限图谱', @scope_graph_code, '验证审计系统和 AuditTrail 的跨文档 canonical、别名、权限记录关系和负边界。', 'AuditTrail,审计系统,权限记录,异常权限扩散,信息安全部,系统管理员', '["审计系统有哪些权限相关要求","审计系统本身是否审批权限或直接回收权限"]', 'list', 'graph_then_evidence', 10, NOW(), NOW(), 1),
(@base_id + 302, 'graph_release_control', 'ReleaseControl 生产发布图谱', @scope_graph_code, '验证 ReleaseControl、CAB、值班 SRE、发布申请、灰度观察和回滚演练的跨文档关系。', 'ReleaseControl,生产发布控制台,CAB,变更评审委员会,值班SRE,回滚演练', '["ReleaseControl 和变更评审委员会、值班 SRE 分别是什么关系","生产发布回滚相关的跨文档图谱社区总结是什么"]', 'list', 'graph_then_evidence', 20, NOW(), NOW(), 1),
(@base_id + 303, 'graph_data_access_guard', 'DataAccessGuard 客户数据图谱', @scope_graph_code, '验证 DataAccessGuard、数据治理负责人、信息安全部、客户数据 Owner 和访问台账的跨文档关系。', 'DataAccessGuard,客户数据访问控制平台,数据治理负责人,信息安全部,客户数据Owner,安全复核组', '["DataAccessGuard 和数据治理负责人、信息安全部分别是什么关系","客户数据访问控制相关的跨文档图谱社区总结是什么"]', 'list', 'graph_then_evidence', 30, NOW(), NOW(), 1),
(@base_id + 304, 'graph_multi_community_boundary', 'GraphRAG 多社区边界', @scope_graph_code, '验证生产发布 community 和客户数据访问 community 的排序、边界和负样例。', '多社区排序,community边界,负样例,职责边界,弱关系外推', '["ReleaseControl 是否负责 L4 高敏感客户数据访问范围确认","DataAccessGuard 是否负责回滚演练和发布窗口管控"]', 'explain', 'graph_assist', 40, NOW(), NOW(), 1)
ON DUPLICATE KEY UPDATE
    topic_name = VALUES(topic_name),
    scope_code = VALUES(scope_code),
    description = VALUES(description),
    aliases = VALUES(aliases),
    examples = VALUES(examples),
    answer_shape = VALUES(answer_shape),
    execution_preference = VALUES(execution_preference),
    sort_order = VALUES(sort_order),
    edit_time = NOW(),
    status = 1;

/* =========================================================
   5. 文档画像配置
   说明：如果系统已自动生成画像，这里会覆盖为更适合验收的手工画像。
   ========================================================= */

INSERT INTO super_agent_document_profile (
    id, document_id, profile_version, document_summary, document_type, core_topics, example_questions,
    graph_friendly, supports_graph_outline, supports_item_lookup, supports_graph_assist,
    profile_source, profile_status, error_msg,
    create_time, edit_time, status
)
VALUES
(@base_id + 401, @doc_o2_provider_pdf_id, 1, 'O2 固定解析样例，用于验证 Document Mind 解析、layout、表格、FIGURE block、bbox、artifact 和后续 RAG 产物联动。', 'spec', '["Document Mind解析","layout","表格解析","FIGURE block","bbox","artifact"]', '["O2 固定样例中的表格能否被识别成结构化表格","这份 PDF 样例是否包含图示或图片区域"]', 0, 1, 1, 0, 'manual', 2, NULL, NOW(), NOW(), 1),
(@base_id + 402, @doc_o2_ocr_pdf_id, 1, 'O2 图片型 PDF OCR 样例，用于验证扫描 PDF 的 OCR 文本、页面图片、表格图片、bbox overlay 和解析观测。', 'spec', '["图片型PDF OCR","PAGE_IMAGE","TABLE_IMAGE","bbox overlay","解析观测"]', '["图片型 PDF OCR 是否识别到了编号条款和业务关键词"]', 0, 1, 1, 0, 'manual', 2, NULL, NOW(), NOW(), 1),
(@base_id + 403, @doc_o2_ocr_png_id, 1, 'O2 PNG 图片 OCR 样例，用于验证图片文件进入解析主链路并识别蓝桥订单、RAG-O2-20260630 和支付回调延迟等关键短语。', 'spec', '["PNG OCR","图片文本","蓝桥订单","RAG-O2-20260630","支付回调延迟"]', '["O2 扫描 OCR 样例里有没有识别到蓝桥订单 7391"]', 0, 1, 1, 0, 'manual', 2, NULL, NOW(), NOW(), 1),
(@base_id + 404, @doc_xinglian_id, 1, '星联智服客服平台上线运营手册，覆盖需求澄清、知识治理、机器人策略设计、灰度验证、生产发布、上线观察、故障应急和运营质量评估。', 'manual', '["客服平台上线","知识治理","机器人策略","灰度验证","上线观察","故障处理","运营质量评估"]', '["检索命中率突然下降的可能原因都有哪些","人工转接率异常升高检查顺序是什么","上线观察与值班规则中观察时长有哪些"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
(@base_id + 405, @doc_release_id, 1, '生产环境发布与回滚操作规范，覆盖发布暂停原则、默认灰度节奏、强制回滚条件、NovaRAG 召回成功率和发布风险控制。', 'rule', '["生产发布","灰度节奏","发布暂停","强制回滚","NovaRAG召回成功率","风险控制"]', '["生产发布默认灰度节奏分几个阶段","强制回滚条件有哪些","哪些情况下默认动作是暂停发布"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
(@base_id + 406, @doc_incident_id, 1, '核心业务系统故障应急响应预案，覆盖故障分级、NovaRAG 检索服务降级、应急处置顺序和升级边界。', 'troubleshooting', '["故障应急","故障分级","NovaRAG降级","检索服务降级","人工转接激增"]', '["NovaRAG 检索服务降级时按什么顺序处理","连续 15 分钟无法返回检索结果故障等级怎么判断"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
(@base_id + 407, @doc_data_policy_id, 1, '客户数据分级与访问控制管理制度，覆盖数据等级、L4 高敏感数据访问、审批层级、导出限制、日志保存和审计要求。', 'rule', '["客户数据分级","L4高敏感","访问审批","DataCleanRoom","日志保存","表格问答"]', '["L4 高敏感信息的审批要求和默认有效期是什么","L3 和 L4 数据的日志保存期限分别是多少"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
(@base_id + 408, @doc_travel_id, 1, '差旅与费用报销管理办法，覆盖住宿标准、报销金额阈值、审批流程和费用合规。', 'rule', '["差旅","费用报销","住宿标准","审批金额阈值","财务BP"]', '["北京出差酒店住宿上限是多少","10000 元以上报销需要哪些审批"]', 0, 1, 1, 0, 'manual', 2, NULL, NOW(), NOW(), 1),
(@base_id + 409, @doc_onboarding_id, 1, '澄星智能新员工入职培训手册，覆盖首周培训日程、培训模块、地点和 30/60/90 天关注重点。', 'manual', '["入职培训","首周日程","培训模块","30天","60天","90天"]', '["入职当天 09:30-10:30 的培训模块是什么","第 30 天、第 60 天、第 90 天分别关注什么"]', 0, 1, 1, 0, 'manual', 2, NULL, NOW(), NOW(), 1),
(@base_id + 410, @doc_audit_evidence_id, 1, 'O6 审计证据规范 A，用于验证 AuditTrail 权限记录、审批记录、回收记录和异常权限扩散证据。', 'rule', '["AuditTrail","审计系统","权限记录","异常权限扩散","信息安全部"]', '["审计系统有哪些权限相关要求","异常权限扩散时 AuditTrail 要保留哪些信息"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
(@base_id + 411, @doc_audit_alias_id, 1, 'O6 审计系统别名说明 B，用于验证审计系统与 AuditTrail 的 canonical/alias，以及不审批、不直接回收权限的负边界。', 'rule', '["AuditTrail别名","审计系统","canonical","负边界"]', '["审计系统本身是否审批权限或直接回收权限"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
(@base_id + 412, @doc_release_graph_spec_id, 1, 'O6 生产发布回滚规范 A，用于验证 ReleaseControl、CAB、值班 SRE、发布申请、灰度观察和回滚演练 community。', 'rule', '["ReleaseControl","CAB","值班SRE","发布申请","灰度观察","回滚演练"]', '["ReleaseControl 和变更评审委员会、值班 SRE 分别是什么关系"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
(@base_id + 413, @doc_release_graph_alias_id, 1, 'O6 生产发布回滚别名 B，用于验证 ReleaseControl、生产发布控制台、变更评审委员会和 CAB 的别名归一。', 'rule', '["ReleaseControl别名","生产发布控制台","变更评审委员会","CAB"]', '["生产发布回滚相关的跨文档图谱社区总结是什么"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
(@base_id + 414, @doc_data_graph_spec_id, 1, 'O6 客户数据访问控制规范 A，用于验证 DataAccessGuard、数据治理负责人、信息安全部和访问台账 community。', 'rule', '["DataAccessGuard","数据治理负责人","信息安全部","访问台账"]', '["DataAccessGuard 和数据治理负责人、信息安全部分别是什么关系"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1),
(@base_id + 415, @doc_data_graph_alias_id, 1, 'O6 客户数据访问控制别名 B，用于验证 DataAccessGuard、客户数据访问控制平台、客户数据 Owner 和安全复核组的别名归一与负边界。', 'rule', '["DataAccessGuard别名","客户数据Owner","安全复核组","负边界"]', '["客户数据访问控制相关的跨文档图谱社区总结是什么","DataAccessGuard 是否负责回滚演练和发布窗口管控"]', 1, 1, 1, 1, 'manual', 2, NULL, NOW(), NOW(), 1)
ON DUPLICATE KEY UPDATE
    profile_version = COALESCE(profile_version, 0) + 1,
    document_summary = VALUES(document_summary),
    document_type = VALUES(document_type),
    core_topics = VALUES(core_topics),
    example_questions = VALUES(example_questions),
    graph_friendly = VALUES(graph_friendly),
    supports_graph_outline = VALUES(supports_graph_outline),
    supports_item_lookup = VALUES(supports_item_lookup),
    supports_graph_assist = VALUES(supports_graph_assist),
    profile_source = VALUES(profile_source),
    profile_status = VALUES(profile_status),
    error_msg = VALUES(error_msg),
    edit_time = NOW(),
    status = 1;

/* =========================================================
   6. 主题文档关联配置
   说明：只清理本脚本引入主题下的旧跨文档关联，不清理其他业务主题。
   ========================================================= */

UPDATE super_agent_topic_document_relation
SET status = 0, edit_time = NOW()
WHERE topic_code IN (
    'o2_parse_artifact',
    'o2_ocr_pdf',
    'o2_ocr_png',
    'operation_service_go_live',
    'operation_release_rollback',
    'operation_incident_response',
    'operation_data_access',
    'operation_travel_reimbursement',
    'operation_onboarding_training',
    'operation_raptor_summary',
    'graph_audit_trail',
    'graph_release_control',
    'graph_data_access_guard',
    'graph_multi_community_boundary'
);

INSERT INTO super_agent_topic_document_relation (
    id, topic_code, document_id, relation_score, relation_source, reason,
    create_time, edit_time, status
)
VALUES
(@base_id + 501, 'o2_parse_artifact', @doc_o2_provider_pdf_id, 0.9800, 'manual', '该样例用于验证普通 PDF 的 Document Mind、layout、表格、FIGURE、bbox 和 artifact。', NOW(), NOW(), 1),
(@base_id + 502, 'o2_ocr_pdf', @doc_o2_ocr_pdf_id, 0.9800, 'manual', '该样例用于验证图片型 PDF OCR、PAGE_IMAGE、TABLE_IMAGE 和 bbox overlay。', NOW(), NOW(), 1),
(@base_id + 503, 'o2_ocr_png', @doc_o2_ocr_png_id, 0.9800, 'manual', '该样例用于验证 PNG 图片 OCR 和关键短语识别。', NOW(), NOW(), 1),

(@base_id + 504, 'operation_service_go_live', @doc_xinglian_id, 0.9900, 'manual', '该手册是星联智服客服平台上线运营和 O8 主基线的核心文档。', NOW(), NOW(), 1),
(@base_id + 505, 'operation_release_rollback', @doc_release_id, 0.9900, 'manual', '该规范集中描述生产发布、灰度节奏、发布暂停和强制回滚条件。', NOW(), NOW(), 1),
(@base_id + 506, 'operation_release_rollback', @doc_xinglian_id, 0.6200, 'manual', '星联智服手册包含上线观察和回滚评估相关内容，可作为发布回滚跨文档对照。', NOW(), NOW(), 1),
(@base_id + 507, 'operation_incident_response', @doc_incident_id, 0.9900, 'manual', '该预案集中描述 NovaRAG 检索服务降级和核心故障应急处理。', NOW(), NOW(), 1),
(@base_id + 508, 'operation_incident_response', @doc_xinglian_id, 0.6500, 'manual', '星联智服手册包含检索命中率下降、回答口径不完整和人工转接率异常等故障处理章节。', NOW(), NOW(), 1),
(@base_id + 509, 'operation_data_access', @doc_data_policy_id, 0.9900, 'manual', '该制度集中描述客户数据分级、L4 数据访问审批、导出限制和日志保存。', NOW(), NOW(), 1),
(@base_id + 510, 'operation_travel_reimbursement', @doc_travel_id, 0.9900, 'manual', '该办法集中描述差旅住宿标准和报销审批金额阈值。', NOW(), NOW(), 1),
(@base_id + 511, 'operation_onboarding_training', @doc_onboarding_id, 0.9900, 'manual', '该手册集中描述新员工入职培训日程和 30/60/90 天关注重点。', NOW(), NOW(), 1),
(@base_id + 512, 'operation_raptor_summary', @doc_xinglian_id, 0.9600, 'manual', '该手册提供客服平台上线、运营监控和质量复盘主线，适合 RAPTOR 总结。', NOW(), NOW(), 1),
(@base_id + 513, 'operation_raptor_summary', @doc_release_id, 0.9400, 'manual', '该规范提供生产发布、灰度验证和回滚评估主线，适合跨文档 RAPTOR 总结。', NOW(), NOW(), 1),
(@base_id + 514, 'operation_raptor_summary', @doc_incident_id, 0.7200, 'manual', '该预案提供故障应急和降级处理干扰样例，用于验证跨文档总结边界。', NOW(), NOW(), 1),

(@base_id + 515, 'graph_audit_trail', @doc_audit_evidence_id, 0.9900, 'manual', '该文档提供 AuditTrail 权限记录和异常权限扩散的关系证据。', NOW(), NOW(), 1),
(@base_id + 516, 'graph_audit_trail', @doc_audit_alias_id, 0.9700, 'manual', '该文档提供审计系统与 AuditTrail 的别名、职责和负边界。', NOW(), NOW(), 1),
(@base_id + 517, 'graph_release_control', @doc_release_graph_spec_id, 0.9900, 'manual', '该文档提供 ReleaseControl、CAB、值班 SRE 和回滚演练关系证据。', NOW(), NOW(), 1),
(@base_id + 518, 'graph_release_control', @doc_release_graph_alias_id, 0.9700, 'manual', '该文档提供 ReleaseControl、生产发布控制台、变更评审委员会和 CAB 的别名归一证据。', NOW(), NOW(), 1),
(@base_id + 519, 'graph_data_access_guard', @doc_data_graph_spec_id, 0.9900, 'manual', '该文档提供 DataAccessGuard、数据治理负责人、信息安全部和访问台账关系证据。', NOW(), NOW(), 1),
(@base_id + 520, 'graph_data_access_guard', @doc_data_graph_alias_id, 0.9700, 'manual', '该文档提供 DataAccessGuard、客户数据访问控制平台、客户数据 Owner 和安全复核组的别名归一证据。', NOW(), NOW(), 1),
(@base_id + 521, 'graph_multi_community_boundary', @doc_release_graph_spec_id, 0.9000, 'manual', '用于验证生产发布回滚 community 在多社区排序中的边界。', NOW(), NOW(), 1),
(@base_id + 522, 'graph_multi_community_boundary', @doc_release_graph_alias_id, 0.8800, 'manual', '用于验证生产发布别名文档不会被客户数据访问问题错误选中。', NOW(), NOW(), 1),
(@base_id + 523, 'graph_multi_community_boundary', @doc_data_graph_spec_id, 0.9000, 'manual', '用于验证客户数据访问 community 在多社区排序中的边界。', NOW(), NOW(), 1),
(@base_id + 524, 'graph_multi_community_boundary', @doc_data_graph_alias_id, 0.8800, 'manual', '用于验证客户数据访问别名文档不会被生产发布问题错误选中。', NOW(), NOW(), 1)
ON DUPLICATE KEY UPDATE
    relation_score = VALUES(relation_score),
    relation_source = VALUES(relation_source),
    reason = VALUES(reason),
    edit_time = NOW(),
    status = 1;

/* =========================================================
   7. 执行后检查
   ========================================================= */

SELECT
    id,
    original_file_name,
    document_name,
    knowledge_scope_code,
    knowledge_scope_name,
    business_category,
    document_tags,
    parse_status,
    strategy_status,
    index_status,
    last_index_task_id
FROM super_agent_document
WHERE id IN (
    @doc_o2_provider_pdf_id,
    @doc_o2_ocr_pdf_id,
    @doc_o2_ocr_png_id,
    @doc_xinglian_id,
    @doc_release_id,
    @doc_incident_id,
    @doc_data_policy_id,
    @doc_travel_id,
    @doc_onboarding_id,
    @doc_audit_evidence_id,
    @doc_audit_alias_id,
    @doc_release_graph_spec_id,
    @doc_release_graph_alias_id,
    @doc_data_graph_spec_id,
    @doc_data_graph_alias_id
)
ORDER BY knowledge_scope_code, original_file_name;

SELECT
    scope_code,
    scope_name,
    aliases,
    sort_order,
    status
FROM super_agent_knowledge_scope_node
WHERE scope_code IN (@scope_parse_code, @scope_operation_code, @scope_graph_code)
ORDER BY sort_order;

SELECT
    topic_code,
    topic_name,
    scope_code,
    answer_shape,
    execution_preference,
    sort_order,
    status
FROM super_agent_knowledge_topic_node
WHERE scope_code IN (@scope_parse_code, @scope_operation_code, @scope_graph_code)
ORDER BY scope_code, sort_order;

SELECT
    p.document_id,
    d.document_name,
    d.knowledge_scope_code,
    p.document_type,
    p.profile_status,
    p.graph_friendly,
    p.supports_graph_outline,
    p.supports_item_lookup,
    p.supports_graph_assist,
    p.status
FROM super_agent_document_profile p
LEFT JOIN super_agent_document d ON d.id = p.document_id
WHERE p.document_id IN (
    @doc_o2_provider_pdf_id,
    @doc_o2_ocr_pdf_id,
    @doc_o2_ocr_png_id,
    @doc_xinglian_id,
    @doc_release_id,
    @doc_incident_id,
    @doc_data_policy_id,
    @doc_travel_id,
    @doc_onboarding_id,
    @doc_audit_evidence_id,
    @doc_audit_alias_id,
    @doc_release_graph_spec_id,
    @doc_release_graph_alias_id,
    @doc_data_graph_spec_id,
    @doc_data_graph_alias_id
)
ORDER BY d.knowledge_scope_code, d.original_file_name;

SELECT
    r.topic_code,
    t.topic_name,
    t.scope_code,
    r.document_id,
    d.document_name,
    r.relation_score,
    r.relation_source,
    r.reason,
    r.status
FROM super_agent_topic_document_relation r
LEFT JOIN super_agent_knowledge_topic_node t ON t.topic_code = r.topic_code
LEFT JOIN super_agent_document d ON d.id = r.document_id
WHERE r.topic_code IN (
    'o2_parse_artifact',
    'o2_ocr_pdf',
    'o2_ocr_png',
    'operation_service_go_live',
    'operation_release_rollback',
    'operation_incident_response',
    'operation_data_access',
    'operation_travel_reimbursement',
    'operation_onboarding_training',
    'operation_raptor_summary',
    'graph_audit_trail',
    'graph_release_control',
    'graph_data_access_guard',
    'graph_multi_community_boundary'
)
ORDER BY t.scope_code, t.sort_order, r.relation_score DESC;

COMMIT;
