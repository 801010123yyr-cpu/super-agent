package org.javaup.ai.manage.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.manage.data.SuperAgentDocumentChunk;
import org.javaup.ai.manage.data.SuperAgentRaptorNode;
import org.javaup.ai.ragtools.model.RagToolsRaptorBuildRequest;
import org.javaup.enums.DocumentChunkSourceTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RaptorDatasetBuildSupportTest {

    private final RaptorDatasetBuildSupport support = new RaptorDatasetBuildSupport(new ObjectMapper());

    @Test
    void prefersDocumentSummaryNodesAndExpandsThemBackToOriginalChunks() {
        SuperAgentRaptorNode summaryNode = summaryNode(9001L, 10L, 100L, 2,
            "[101,102,103]", "[201,202]");
        SuperAgentDocumentChunk rawChunk = rawChunk(101L, 10L, 100L, 201L);

        RaptorDatasetBuildSupport.DatasetInputs inputs =
            support.buildInputs(List.of(summaryNode), List.of(rawChunk));

        assertThat(inputs.inputMode()).isEqualTo("DOCUMENT_RAPTOR_SUMMARY");
        assertThat(inputs.inputs()).hasSize(1);
        assertThat(inputs.reusableSummaryInputCount()).isEqualTo(1);
        assertThat(inputs.originalChunkInputCount()).isZero();
        assertThat(inputs.inputs().get(0).chunkId()).isEqualTo(9001L);
        assertThat(inputs.inputs().get(0).chunkType()).isEqualTo("RAPTOR_SUMMARY");
        assertThat(inputs.expandSourceChunkIds(List.of(9001L))).containsExactly(101L, 102L, 103L);
        assertThat(inputs.expandSourceParentBlockIds(List.of(9001L))).containsExactly(201L, 202L);
    }

    @Test
    void fallsBackToOriginalChunksWhenNoReusableSummaryExists() {
        SuperAgentDocumentChunk rawChunk = rawChunk(101L, 10L, 100L, 201L);

        RaptorDatasetBuildSupport.DatasetInputs inputs =
            support.buildInputs(List.of(), List.of(rawChunk));

        assertThat(inputs.inputMode()).isEqualTo("ORIGINAL_CHUNK");
        assertThat(inputs.inputs()).hasSize(1);
        assertThat(inputs.reusableSummaryInputCount()).isZero();
        assertThat(inputs.originalChunkInputCount()).isEqualTo(1);
        assertThat(inputs.inputs().get(0).chunkId()).isEqualTo(101L);
        assertThat(inputs.expandSourceChunkIds(List.of(101L))).containsExactly(101L);
        assertThat(inputs.expandSourceParentBlockIds(List.of(101L))).containsExactly(201L);
    }

    @Test
    void convertsDatasetInputToRagToolsChunkWithTraceMetadata() {
        RaptorDatasetBuildSupport.DatasetInput input = RaptorDatasetBuildSupport.DatasetInput.fromSummaryNode(
            summaryNode(9001L, 10L, 100L, 3, "[101]", "[201]"),
            List.of(101L),
            List.of(201L)
        );

        RagToolsRaptorBuildRequest.Chunk chunk = support.toRequestChunk(input, 7);

        assertThat(chunk.getChunkId()).isEqualTo(9001L);
        assertThat(chunk.getChunkNo()).isEqualTo(7);
        assertThat(chunk.getText()).contains("摘要 9001");
        assertThat(chunk.getBboxJson()).isEmpty();
        assertThat(chunk.getSourceBlockIds()).isEmpty();
        assertThat(chunk.getMetadata())
            .containsEntry("datasetInputType", "DOCUMENT_RAPTOR_SUMMARY")
            .containsEntry("sourceRaptorNodeId", 9001L)
            .containsEntry("documentId", 10L)
            .containsEntry("taskId", 100L);
    }

    @Test
    void normalizesNullableStringFieldsBeforeCallingRagTools() {
        RaptorDatasetBuildSupport.DatasetInput input = new RaptorDatasetBuildSupport.DatasetInput(
            9002L,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "DOCUMENT_RAPTOR_SUMMARY",
            10L,
            100L,
            9002L,
            2,
            List.of(101L),
            List.of()
        );

        RagToolsRaptorBuildRequest.Chunk chunk = support.toRequestChunk(input, 8);

        assertThat(chunk.getChunkType()).isEmpty();
        assertThat(chunk.getTitle()).isEmpty();
        assertThat(chunk.getSectionPath()).isEmpty();
        assertThat(chunk.getPageRange()).isEmpty();
        assertThat(chunk.getBboxJson()).isEmpty();
        assertThat(chunk.getText()).isEmpty();
        assertThat(chunk.getContentWithWeight()).isEmpty();
        assertThat(chunk.getSourceBlockIds()).isEmpty();
    }

    private static SuperAgentRaptorNode summaryNode(Long id,
                                                    Long documentId,
                                                    Long taskId,
                                                    Integer level,
                                                    String sourceChunkIdsJson,
                                                    String sourceParentBlockIdsJson) {
        SuperAgentRaptorNode node = new SuperAgentRaptorNode();
        node.setId(id);
        node.setDocumentId(documentId);
        node.setTaskId(taskId);
        node.setScopeType(RaptorScopeSupport.SCOPE_TYPE_DOCUMENT);
        node.setScopeKey(RaptorScopeSupport.documentScopeKey(documentId));
        node.setNodeLevel(level);
        node.setNodeNo(1);
        node.setTitle("标题 " + id);
        node.setSummary("摘要 " + id);
        node.setSummaryWithWeight("加权摘要 " + id);
        node.setSourceChunkIdsJson(sourceChunkIdsJson);
        node.setSourceParentBlockIdsJson(sourceParentBlockIdsJson);
        node.setSectionPath("章节");
        node.setPageRange("1-2");
        return node;
    }

    private static SuperAgentDocumentChunk rawChunk(Long id, Long documentId, Long taskId, Long parentBlockId) {
        SuperAgentDocumentChunk chunk = new SuperAgentDocumentChunk();
        chunk.setId(id);
        chunk.setDocumentId(documentId);
        chunk.setTaskId(taskId);
        chunk.setParentBlockId(parentBlockId);
        chunk.setChunkNo(1);
        chunk.setSourceType(DocumentChunkSourceTypeEnum.ORIGINAL.getCode());
        chunk.setChunkType("TEXT");
        chunk.setTitle("原文标题");
        chunk.setSectionPath("原文章节");
        chunk.setChunkText("原文内容");
        chunk.setContentWithWeight("原文加权内容");
        return chunk;
    }
}
