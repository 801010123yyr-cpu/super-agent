package org.javaup.ai.manage.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.data.SuperAgentRaptorNode;
import org.javaup.ai.manage.mapper.SuperAgentDocumentChunkMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentRaptorNodeMapper;
import org.javaup.ai.manage.model.raptor.RaptorSearchResult;
import org.javaup.ai.manage.service.RaptorSearchService;
import org.javaup.ai.manage.service.RaptorSummaryIndexService;
import org.javaup.ai.manage.support.DocumentPgVectorConstants;
import org.javaup.ai.manage.support.RaptorScopeSupport;
import org.javaup.enums.BusinessStatus;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@AllArgsConstructor
@Service
public class RaptorSearchServiceImpl implements RaptorSearchService {

    private static final String RAPTOR_RETRIEVE_SQL_TEMPLATE = """
        SELECT
            id,
            document_id,
            task_id,
            scope_type,
            scope_key,
            node_level,
            node_no,
            title,
            summary,
            summary_with_weight,
            source_chunk_ids_json,
            source_parent_block_ids_json,
            section_path,
            page_range,
            keywords,
            questions,
            1 - (embedding <=> CAST(? AS vector)) AS similarity_score
        FROM %s
        WHERE status = 1
          AND (
            (document_id IN (%s) AND task_id IN (%s))
            %s
          )
        ORDER BY embedding <=> CAST(? AS vector)
        LIMIT ?
        """;

    private static final Pattern ALNUM_TERM_PATTERN = Pattern.compile("[a-z0-9._-]{2,}");

    private static final Pattern CHINESE_TERM_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");

    @Qualifier("documentManagePgVectorJdbcTemplate")
    private final JdbcTemplate pgVectorJdbcTemplate;

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    private final SuperAgentRaptorNodeMapper raptorNodeMapper;

    private final SuperAgentDocumentMapper documentMapper;

    private final SuperAgentDocumentChunkMapper chunkMapper;

    private final ObjectMapper objectMapper;

    private final ObjectProvider<RaptorSummaryIndexService> raptorSummaryIndexServiceProvider;

    @Override
    @Transactional(readOnly = true)
    public List<RaptorSearchResult> search(String question,
                                           List<Long> documentIds,
                                           List<Long> taskIds,
                                           int topK,
                                           int sourceChunkTopK) {
        if (StrUtil.isBlank(question) || CollUtil.isEmpty(documentIds) || CollUtil.isEmpty(taskIds) || topK <= 0) {
            return List.of();
        }

        List<SuperAgentDocument> scopeDocuments = loadScopeDocuments(documentIds);
        List<String> datasetScopeKeys = RaptorScopeSupport.searchScopeKeys(scopeDocuments);
        List<RaptorNodeHit> nodeHits = retrieveSummaryNodes(question, documentIds, taskIds, datasetScopeKeys, Math.max(topK * 3, topK));
        if (nodeHits.isEmpty()) {
            return List.of();
        }

        Map<Long, SuperAgentRaptorNode> nodeMap = loadNodes(nodeHits);
        List<String> terms = extractTerms(question);
        Set<Long> allowedDocumentIds = new LinkedHashSet<>(documentIds);
        Set<Long> allowedTaskIds = new LinkedHashSet<>(taskIds);
        Map<Long, RaptorSearchResult> resultMap = new LinkedHashMap<>();
        for (RaptorNodeHit hit : nodeHits) {
            SuperAgentRaptorNode node = nodeMap.get(hit.nodeId());
            if (node == null) {
                continue;
            }
            List<SuperAgentDocumentChunk> chunks = loadSourceChunks(node, sourceChunkTopK, allowedDocumentIds, allowedTaskIds);
            for (SuperAgentDocumentChunk chunk : chunks) {
                if (chunk == null || chunk.getId() == null) {
                    continue;
                }
                double score = hit.score() + chunkEvidenceBoost(chunk, terms);
                RaptorSearchResult result = toResult(node, chunk, score);
                resultMap.merge(chunk.getId(), result,
                    (left, right) -> left.getScore() >= right.getScore() ? left : right);
            }
        }

        return resultMap.values().stream()
            .sorted(Comparator.comparingDouble(RaptorSearchResult::getScore).reversed())
            .limit(topK)
            .toList();
    }

    private List<RaptorNodeHit> retrieveSummaryNodes(String question,
                                                     List<Long> documentIds,
                                                     List<Long> taskIds,
                                                     List<String> datasetScopeKeys,
                                                     int topK) {
        Map<Long, RaptorNodeHit> mergedHits = new LinkedHashMap<>();
        List<RaptorNodeHit> vectorHits = retrieveVectorSummaryNodes(question, documentIds, taskIds, datasetScopeKeys, topK);
        mergeHits(mergedHits, vectorHits, 1.0D);
        RaptorSummaryIndexService indexService = raptorSummaryIndexServiceProvider.getIfAvailable();
        if (indexService != null) {
            List<RaptorSummaryIndexService.RaptorSummaryHit> lexicalHits = indexService.search(question, documentIds, taskIds, datasetScopeKeys, topK);
            double maxLexicalScore = lexicalHits.stream()
                .mapToDouble(RaptorSummaryIndexService.RaptorSummaryHit::score)
                .max()
                .orElse(0D);
            List<RaptorNodeHit> normalizedLexicalHits = lexicalHits.stream()
                .map(hit -> new RaptorNodeHit(hit.nodeId(), normalizeLexicalScore(hit.score(), maxLexicalScore)))
                .toList();
            mergeHits(mergedHits, normalizedLexicalHits, 0.85D);
        }
        return mergedHits.values().stream()
            .sorted(Comparator.comparingDouble(RaptorNodeHit::score).reversed())
            .limit(Math.max(1, Math.min(topK, 50)))
            .toList();
    }

    private List<RaptorNodeHit> retrieveVectorSummaryNodes(String question,
                                                           List<Long> documentIds,
                                                           List<Long> taskIds,
                                                           List<String> datasetScopeKeys,
                                                           int topK) {
        EmbeddingModel embeddingModel = requireEmbeddingModel();
        String questionVector = toVectorLiteral(embeddingModel.embed(question.trim()));
        String datasetScopeClause = CollUtil.isEmpty(datasetScopeKeys)
            ? ""
            : " OR (scope_type = ? AND scope_key IN (" + buildPlaceholders(datasetScopeKeys.size()) + "))";
        String sql = RAPTOR_RETRIEVE_SQL_TEMPLATE.formatted(
            DocumentPgVectorConstants.RAPTOR_EMBEDDING_TABLE_NAME,
            buildPlaceholders(documentIds.size()),
            buildPlaceholders(taskIds.size()),
            datasetScopeClause
        );
        List<Object> params = new ArrayList<>();
        params.add(questionVector);
        params.addAll(documentIds);
        params.addAll(taskIds);
        if (CollUtil.isNotEmpty(datasetScopeKeys)) {
            params.add(RaptorScopeSupport.SCOPE_TYPE_DATASET);
            params.addAll(datasetScopeKeys);
        }
        params.add(questionVector);
        params.add(Math.max(1, Math.min(topK, 50)));

        return pgVectorJdbcTemplate.query(sql, params.toArray(), (resultSet, rowNum) -> new RaptorNodeHit(
            resultSet.getLong("id"),
            resultSet.getDouble("similarity_score")
        ));
    }

    private void mergeHits(Map<Long, RaptorNodeHit> target, List<RaptorNodeHit> source, double weight) {
        if (source == null) {
            return;
        }
        for (RaptorNodeHit hit : source) {
            if (hit == null || hit.nodeId() == null) {
                continue;
            }
            double weightedScore = hit.score() * weight;
            target.merge(hit.nodeId(), new RaptorNodeHit(hit.nodeId(), weightedScore),
                (left, right) -> new RaptorNodeHit(left.nodeId(), Math.max(left.score(), right.score()) + Math.min(left.score(), right.score()) * 0.15D));
        }
    }

    private double normalizeLexicalScore(double score, double maxScore) {
        if (score <= 0D || maxScore <= 0D) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, score / maxScore));
    }

    private Map<Long, SuperAgentRaptorNode> loadNodes(List<RaptorNodeHit> nodeHits) {
        List<Long> nodeIds = nodeHits.stream()
            .map(RaptorNodeHit::nodeId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
        if (nodeIds.isEmpty()) {
            return Map.of();
        }
        return raptorNodeMapper.selectBatchIds(nodeIds).stream()
            .collect(Collectors.toMap(
                SuperAgentRaptorNode::getId,
                node -> node,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private List<SuperAgentDocumentChunk> loadSourceChunks(SuperAgentRaptorNode node,
                                                           int sourceChunkTopK,
                                                           Set<Long> allowedDocumentIds,
                                                           Set<Long> allowedTaskIds) {
        List<Long> chunkIds = readLongList(node.getSourceChunkIdsJson());
        if (chunkIds.isEmpty()) {
            return List.of();
        }
        List<SuperAgentDocumentChunk> chunks = chunkMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentChunk>()
            .in(SuperAgentDocumentChunk::getId, chunkIds)
            .eq(SuperAgentDocumentChunk::getStatus, BusinessStatus.YES.getCode()));
        Map<Long, Integer> orderMap = new LinkedHashMap<>();
        for (int index = 0; index < chunkIds.size(); index++) {
            orderMap.put(chunkIds.get(index), index);
        }
        return chunks.stream()
            .filter(chunk -> allowedDocumentIds.contains(chunk.getDocumentId()))
            .filter(chunk -> allowedTaskIds.contains(chunk.getTaskId()))
            .sorted(Comparator.comparingInt(chunk -> orderMap.getOrDefault(chunk.getId(), Integer.MAX_VALUE)))
            .limit(Math.max(1, sourceChunkTopK))
            .toList();
    }

    private List<SuperAgentDocument> loadScopeDocuments(List<Long> documentIds) {
        if (CollUtil.isEmpty(documentIds)) {
            return List.of();
        }
        return documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
            .in(SuperAgentDocument::getId, documentIds)
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode()));
    }

    private RaptorSearchResult toResult(SuperAgentRaptorNode node, SuperAgentDocumentChunk chunk, double score) {
        return RaptorSearchResult.builder()
            .documentId(chunk.getDocumentId())
            .taskId(chunk.getTaskId())
            .raptorNodeId(node.getId())
            .raptorNodeTitle(node.getTitle())
            .raptorNodeLevel(node.getNodeLevel())
            .raptorSummary(node.getSummary())
            .chunkId(chunk.getId())
            .parentBlockId(chunk.getParentBlockId())
            .chunkNo(chunk.getChunkNo())
            .chunkText(chunk.getChunkText())
            .title(chunk.getTitle())
            .sectionPath(StrUtil.blankToDefault(chunk.getSectionPath(), node.getSectionPath()))
            .pageNo(chunk.getPageNo())
            .pageRange(StrUtil.blankToDefault(chunk.getPageRange(), node.getPageRange()))
            .bboxJson(chunk.getBboxJson())
            .sourceBlockIds(chunk.getSourceBlockIds())
            .score(score)
            .build();
    }

    private double chunkEvidenceBoost(SuperAgentDocumentChunk chunk, List<String> terms) {
        if (chunk == null || terms.isEmpty()) {
            return 0D;
        }
        String text = normalize(String.join(" ",
            StrUtil.blankToDefault(chunk.getTitle(), ""),
            StrUtil.blankToDefault(chunk.getSectionPath(), ""),
            StrUtil.blankToDefault(chunk.getContentWithWeight(), ""),
            StrUtil.blankToDefault(chunk.getChunkText(), "")
        ));
        double boost = 0D;
        for (String term : terms) {
            if (term.length() >= 2 && text.contains(term)) {
                boost += 0.03D;
            }
        }
        return Math.min(boost, 0.12D);
    }

    private List<String> extractTerms(String question) {
        String normalized = normalize(question);
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher alnumMatcher = ALNUM_TERM_PATTERN.matcher(normalized);
        while (alnumMatcher.find()) {
            terms.add(alnumMatcher.group());
        }
        Matcher chineseMatcher = CHINESE_TERM_PATTERN.matcher(normalized);
        while (chineseMatcher.find()) {
            String value = chineseMatcher.group();
            if (value.length() <= 6) {
                terms.add(value);
                continue;
            }
            for (int index = 0; index < value.length() - 1; index += 2) {
                terms.add(value.substring(index, Math.min(index + 4, value.length())));
            }
        }
        return terms.stream().limit(20).toList();
    }

    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "")
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]{}]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private EmbeddingModel requireEmbeddingModel() {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException("当前未找到可用的 EmbeddingModel，无法执行 RAPTOR 摘要检索。");
        }
        return embeddingModel;
    }

    private String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalStateException("问题向量生成失败，无法执行 RAPTOR 检索。");
        }
        StringBuilder vectorBuilder = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {
            if (index > 0) {
                vectorBuilder.append(',');
            }
            vectorBuilder.append(embedding[index]);
        }
        vectorBuilder.append(']');
        return vectorBuilder.toString();
    }

    private List<Long> readLongList(String json) {
        if (StrUtil.isBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Long.class));
        }
        catch (Exception exception) {
            return List.of();
        }
    }

    private String buildPlaceholders(int size) {
        return IntStream.range(0, size)
            .mapToObj(index -> "?")
            .collect(Collectors.joining(","));
    }

    private record RaptorNodeHit(Long nodeId, double score) {
    }
}
