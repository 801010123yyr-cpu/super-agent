package org.javaup.ai.manage.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentBlock;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyPlan;
import org.javaup.ai.manage.data.SuperAgentDocumentStrategyStep;
import org.javaup.ai.manage.service.DocumentStructureNodeService;
import org.javaup.ai.manage.support.KnowledgeBaseIndexingConfigResolver;
import org.javaup.ai.manage.support.ParentBlockCandidate;
import org.javaup.ai.prompt.PromptTemplateService;
import org.javaup.enums.DocumentStrategyPipelineTypeEnum;
import org.javaup.enums.DocumentStrategyTypeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStrategyServiceImplTest {

    @Test
    void buildParentBlocksUsesStructuredDocumentBlocks() {
        DocumentStrategyServiceImpl service = new DocumentStrategyServiceImpl(
            new DocumentManageProperties(),
            new ObjectMapper(),
            new EmptyChatModelProvider(),
            new EmptyDocumentStructureNodeService(),
            new PromptTemplateService(null),
            new KnowledgeBaseIndexingConfigResolver(new DocumentManageProperties())
        );

        SuperAgentDocument document = new SuperAgentDocument();
        document.setId(100L);
        document.setLastParseTaskId(200L);

        List<ParentBlockCandidate> parents = service.buildParentBlocks(
            document,
            new SuperAgentDocumentStrategyPlan(),
            List.of(parentStructureStep(), childRecursiveStep()),
            List.of(
                block(11L, 1, "TITLE", "报销制度", "报销制度", 1, null),
                block(12L, 2, "TEXT", "报销制度", "出差结束后 10 个工作日内提交报销。", 2, "{\"x0\":1,\"y0\":2,\"x1\":3,\"y1\":4}")
            )
        );

        assertThat(parents).hasSize(1);
        ParentBlockCandidate parent = parents.get(0);
        assertThat(parent.getSourceBlockIds()).isEqualTo("[11,12]");
        assertThat(parent.getPageRange()).isEqualTo("1-2");
        assertThat(parent.getChildChunks()).hasSize(2);
        assertThat(parent.getChildChunks().get(1).getSourceBlockIds()).isEqualTo("[12]");
        assertThat(parent.getChildChunks().get(1).getPageNo()).isEqualTo(2);
        assertThat(parent.getChildChunks().get(1).getBboxJson()).contains("\"x0\":1");
        assertThat(parent.getChildChunks().get(1).getChunkType()).isEqualTo("TEXT");
        assertThat(parent.getChildChunks().get(1).getTitle()).isEqualTo("报销制度");
        assertThat(parent.getChildChunks().get(1).getContentWithWeight())
            .contains("[TITLE]", "报销制度", "[SECTION]", "[CONTENT]");
        assertThat(parent.getChildChunks().get(1).getKeywords()).contains("报销制度");
        assertThat(parent.getChildChunks().get(1).getQuestions()).contains("核心内容");
    }

    private static SuperAgentDocumentStrategyStep parentStructureStep() {
        SuperAgentDocumentStrategyStep step = new SuperAgentDocumentStrategyStep();
        step.setPipelineType(DocumentStrategyPipelineTypeEnum.PARENT.getCode());
        step.setStepNo(1);
        step.setStrategyType(DocumentStrategyTypeEnum.STRUCTURE.getCode());
        return step;
    }

    private static SuperAgentDocumentStrategyStep childRecursiveStep() {
        SuperAgentDocumentStrategyStep step = new SuperAgentDocumentStrategyStep();
        step.setPipelineType(DocumentStrategyPipelineTypeEnum.CHILD.getCode());
        step.setStepNo(1);
        step.setStrategyType(DocumentStrategyTypeEnum.RECURSIVE.getCode());
        return step;
    }

    private static SuperAgentDocumentBlock block(Long id,
                                                 Integer blockNo,
                                                 String blockType,
                                                 String sectionPath,
                                                 String text,
                                                 Integer pageNo,
                                                 String bboxJson) {
        SuperAgentDocumentBlock block = new SuperAgentDocumentBlock();
        block.setId(id);
        block.setBlockNo(blockNo);
        block.setBlockType(blockType);
        block.setSectionPath(sectionPath);
        block.setCanonicalPath(sectionPath);
        block.setText(text);
        block.setContentWithWeight(text);
        block.setPageNo(pageNo);
        block.setBboxJson(bboxJson);
        return block;
    }

    private static class EmptyDocumentStructureNodeService implements DocumentStructureNodeService {

        @Override
        public List<org.javaup.ai.manage.data.SuperAgentDocumentStructureNode> replaceDocumentNodes(
            Long documentId,
            Long parseTaskId,
            List<org.javaup.ai.manage.support.DocumentStructureNodeCandidate> candidates) {
            return List.of();
        }

        @Override
        public List<org.javaup.ai.manage.data.SuperAgentDocumentStructureNode> listDocumentNodes(Long documentId, Long parseTaskId) {
            return List.of();
        }

        @Override
        public Map<Long, org.javaup.ai.manage.data.SuperAgentDocumentStructureNode> nodeMap(Long documentId, Long parseTaskId) {
            return Map.of();
        }

        @Override
        public void deleteByDocumentId(Long documentId) {
        }
    }

    private static class EmptyChatModelProvider implements ObjectProvider<ChatModel> {

        @Override
        public ChatModel getObject(Object... args) throws BeansException {
            return null;
        }

        @Override
        public ChatModel getIfAvailable() throws BeansException {
            return null;
        }

        @Override
        public ChatModel getIfUnique() throws BeansException {
            return null;
        }

        @Override
        public ChatModel getObject() throws BeansException {
            return null;
        }

        @Override
        public void forEach(Consumer<? super ChatModel> action) {
        }

        @Override
        public Stream<ChatModel> stream() {
            return Stream.empty();
        }
    }
}
