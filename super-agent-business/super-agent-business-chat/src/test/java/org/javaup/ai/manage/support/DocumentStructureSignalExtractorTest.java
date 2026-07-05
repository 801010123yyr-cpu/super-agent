package org.javaup.ai.manage.support;

import org.javaup.ai.manage.config.DocumentManageProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStructureSignalExtractorTest {

    @Test
    void markdownHeadingFollowedByOrderedListKeepsListItemsAsListItems() {
        DocumentStructureSignalExtractor extractor = new DocumentStructureSignalExtractor(
            new DocumentManageProperties(),
            new DocumentLineClassifier()
        );

        DocumentStructureSignalBatch batch = extractor.extract("测试文档", """
            # 14.1.2 可能原因
            1. 新版本切块异常。
            2. 父子块配置错误。
            3. 向量索引构建不完整。
            """);

        List<DocumentStructureSignal> signals = batch.signals();
        assertThat(signals)
            .filteredOn(signal -> signal.getLineNo() > 0)
            .filteredOn(signal -> signal.getKind() != DocumentStructureSignalKind.BLANK)
            .extracting(DocumentStructureSignal::getKind)
            .containsExactly(
                DocumentStructureSignalKind.HEADING,
                DocumentStructureSignalKind.LIST_ITEM,
                DocumentStructureSignalKind.LIST_ITEM,
                DocumentStructureSignalKind.LIST_ITEM
            );
    }

    @Test
    void collapsedOrderedListIsNotPromotedToHeadingCandidate() {
        DocumentStructureSignalExtractor extractor = new DocumentStructureSignalExtractor(
            new DocumentManageProperties(),
            new DocumentLineClassifier()
        );

        DocumentStructureSignalBatch batch = extractor.extract("测试文档",
            "1. 新版本切块异常。 2. 父子块配置错误。 3. 向量索引构建不完整。");

        DocumentStructureSignal signal = batch.signals().stream()
            .filter(item -> item.getLineNo() > 0)
            .findFirst()
            .orElseThrow();
        assertThat(signal.getKind()).isEqualTo(DocumentStructureSignalKind.LIST_ITEM);
        assertThat(signal.getReasons()).contains("collapsed-ordered-list");
    }
}
