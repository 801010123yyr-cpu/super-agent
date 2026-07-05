package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagRuntimeOptions {

    private int vectorTopK;

    private int keywordTopK;

    private int graphRagTopK;

    private int graphRagMaxHops;

    private int raptorTopK;

    private int raptorSourceChunkTopK;

    private int candidateTopK;

    private int rerankCandidateTopK;

    private int reserveCandidateTopK;

    private int finalTopK;

    private double minVectorSimilarity;

    private double keywordRelativeScoreFloor;

    private boolean keywordChannelEnabled;

    private boolean tableChannelEnabled;

    private boolean graphRagChannelEnabled;

    private boolean raptorChannelEnabled;

    private HybridOptions hybrid;

    @Builder.Default
    private List<String> kbConfigConflictFields = new ArrayList<>();

    public static RagRuntimeOptions from(ChatRagProperties properties) {
        ChatRagProperties.HybridProperties hybridProperties = properties == null ? null : properties.getHybrid();
        return RagRuntimeOptions.builder()
            .vectorTopK(properties == null ? 8 : properties.getVectorTopK())
            .keywordTopK(properties == null ? 8 : properties.getKeywordTopK())
            .graphRagTopK(properties == null ? 5 : properties.getGraphRagTopK())
            .graphRagMaxHops(properties == null ? 2 : properties.getGraphRagMaxHops())
            .raptorTopK(properties == null ? 5 : properties.getRaptorTopK())
            .raptorSourceChunkTopK(properties == null ? 3 : properties.getRaptorSourceChunkTopK())
            .candidateTopK(properties == null ? 10 : properties.getCandidateTopK())
            .rerankCandidateTopK(properties == null ? 16 : properties.getRerankCandidateTopK())
            .reserveCandidateTopK(properties == null ? 8 : properties.getReserveCandidateTopK())
            .finalTopK(properties == null ? 5 : properties.getFinalTopK())
            .minVectorSimilarity(properties == null ? 0.45D : properties.getMinVectorSimilarity())
            .keywordRelativeScoreFloor(properties == null ? 0.35D : properties.getKeywordRelativeScoreFloor())
            .keywordChannelEnabled(properties == null || properties.isKeywordChannelEnabled())
            .tableChannelEnabled(properties == null || properties.isTableChannelEnabled())
            .graphRagChannelEnabled(properties == null || properties.isGraphRagChannelEnabled())
            .raptorChannelEnabled(properties == null || properties.isRaptorChannelEnabled())
            .hybrid(HybridOptions.from(hybridProperties))
            .kbConfigConflictFields(new ArrayList<>())
            .build();
    }

    public static RagRuntimeOptions resolve(ConversationExecutionPlan plan, ChatRagProperties properties) {
        return plan == null || plan.getRagRuntimeOptions() == null
            ? from(properties)
            : plan.getRagRuntimeOptions();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HybridOptions {

        private double vectorWeight;

        private double keywordWeight;

        private double tableWeight;

        private double graphRagWeight;

        private double raptorWeight;

        private double rankWeight;

        private double originalScoreWeight;

        private double metadataBoostWeight;

        private double maxMetadataBoost;

        public static HybridOptions from(ChatRagProperties.HybridProperties properties) {
            return HybridOptions.builder()
                .vectorWeight(properties == null ? 1.0D : properties.getVectorWeight())
                .keywordWeight(properties == null ? 1.0D : properties.getKeywordWeight())
                .tableWeight(properties == null ? 1.2D : properties.getTableWeight())
                .graphRagWeight(properties == null ? 1.1D : properties.getGraphRagWeight())
                .raptorWeight(properties == null ? 1.05D : properties.getRaptorWeight())
                .rankWeight(properties == null ? 1.0D : properties.getRankWeight())
                .originalScoreWeight(properties == null ? 0.08D : properties.getOriginalScoreWeight())
                .metadataBoostWeight(properties == null ? 0.04D : properties.getMetadataBoostWeight())
                .maxMetadataBoost(properties == null ? 1.0D : properties.getMaxMetadataBoost())
                .build();
        }
    }
}
