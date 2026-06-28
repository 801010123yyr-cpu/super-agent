package org.javaup.ai.chatagent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 配置属性
 * @author: 阿星不是程序员
 **/

@Data
@Component
@ConfigurationProperties(prefix = "app.chat.rag")
public class ChatRagProperties {

    private boolean enabled = true;

    private boolean rewriteEnabled = true;

    private int rewriteHistoryTurns = 4;

    private RewriteOptionsProperties rewriteOptions = new RewriteOptionsProperties();

    private int maxSubQuestions = 4;

    private int vectorTopK = 8;

    private int keywordTopK = 8;

    private int graphRagTopK = 5;

    private int graphRagMaxHops = 2;

    private GraphRagQueryPlanProperties graphRagQueryPlan = new GraphRagQueryPlanProperties();

    private AutoRouteProperties autoRoute = new AutoRouteProperties();

    private int raptorTopK = 5;

    private int raptorSourceChunkTopK = 3;

    private int raptorMaxClusterSize = 6;

    private int raptorMaxLevels = 3;

    private boolean raptorLlmSummaryEnabled = true;

    private double raptorSummaryQualityFloor = 0.42D;

    private int candidateTopK = 10;

    private int finalTopK = 5;

    private double minVectorSimilarity = 0.45D;

    private double keywordRelativeScoreFloor = 0.35D;

    private int parentEvidenceMaxChars = 2200;

    private int planningHistoryMaxChars = 1600;

    private int answerHistoryMaxChars = 1000;

    private int totalEvidenceMaxChars = 5200;

    private int perSubQuestionEvidenceMaxChars = 2200;

    private long channelTimeoutMs = 5000L;

    private long subQuestionTimeoutMs = 12000L;

    private boolean keywordChannelEnabled = true;

    private boolean tableChannelEnabled = true;

    private boolean graphRagChannelEnabled = true;

    private boolean raptorChannelEnabled = true;

    private HybridProperties hybrid = new HybridProperties();

    private boolean rerankEnabled = true;

    private String noEvidenceReply = "当前没有从已接入文档中检索到足够证据，暂时不能给出可靠结论。";

    private String answerSystemPrompt = "";

    private HistorySummaryProperties historySummary = new HistorySummaryProperties();

    @Data
    public static class HistorySummaryProperties {

        private boolean enabled = true;

        private int keepRecentTurns = 4;

        private int compressionBatchTurns = 6;

        private int recentTranscriptMaxChars = 2200;

        private int summaryMaxChars = 1400;
    }

    @Data
    public static class RewriteOptionsProperties {

        private boolean enabled = true;

        private Double temperature = 0.1D;

        private Double topP = 0.3D;

        private Boolean thinking = Boolean.FALSE;
    }

    @Data
    public static class GraphRagQueryPlanProperties {

        private boolean enabled = true;
    }

    @Data
    public static class AutoRouteProperties {

        private double confidentDocumentThreshold = 0.55D;

        private double multiDocumentRetrievalThreshold = 0.45D;
    }

    @Data
    public static class HybridProperties {

        private double vectorWeight = 1.0D;

        private double keywordWeight = 1.0D;

        private double tableWeight = 1.2D;

        private double graphRagWeight = 1.1D;

        private double raptorWeight = 1.05D;

        private double rankWeight = 1.0D;

        private double originalScoreWeight = 0.08D;

        private double metadataBoostWeight = 0.04D;

        private double maxMetadataBoost = 1.0D;
    }
}
