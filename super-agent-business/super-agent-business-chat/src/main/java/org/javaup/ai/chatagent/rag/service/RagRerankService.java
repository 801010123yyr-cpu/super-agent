package org.javaup.ai.chatagent.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.rag.config.ChatRagProperties;
import org.javaup.ai.ragtools.client.RagToolsClient;
import org.javaup.ai.ragtools.model.RagToolsRerankRequest;
import org.javaup.ai.ragtools.model.RagToolsRerankResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class RagRerankService {

    private static final String RERANK_SCORE_KEY = "rerankScore";
    private static final String RERANK_RANK_KEY = "rerankRank";
    private static final String RERANK_MODEL_KEY = "rerankModel";

    private final RagToolsClient ragToolsClient;
    private final ChatRagProperties properties;

    public RagRerankService(RagToolsClient ragToolsClient,
                            ChatRagProperties properties) {
        this.ragToolsClient = ragToolsClient;
        this.properties = properties;
    }

    public List<Document> rerank(String query, List<Document> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        int topK = Math.min(Math.max(properties.getFinalTopK(), 1), candidates.size());
        Map<String, Document> documentMap = new LinkedHashMap<>();
        List<RagToolsRerankRequest.Candidate> requestCandidates = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            Document document = candidates.get(index);
            String documentId = document.getId() == null || document.getId().isBlank()
                ? ""
                : document.getId();
            String candidateId = index + ":" + documentId;
            documentMap.put(candidateId, document);
            requestCandidates.add(new RagToolsRerankRequest.Candidate(
                candidateId,
                document.getText(),
                new LinkedHashMap<>(document.getMetadata())
            ));
        }

        RagToolsRerankResponse response = ragToolsClient.rerank(new RagToolsRerankRequest(
            query,
            requestCandidates,
            topK
        ));
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            throw new IllegalStateException("rag-tools rerank 返回结果为空");
        }

        List<Document> rerankedDocuments = new ArrayList<>(response.getResults().size());
        for (RagToolsRerankResponse.Result result : response.getResults()) {
            if (result == null || result.getId() == null || !documentMap.containsKey(result.getId())) {
                throw new IllegalStateException("rag-tools rerank 返回了未知候选 id: " + (result == null ? "" : result.getId()));
            }
            Document document = documentMap.get(result.getId());
            document.getMetadata().put(RERANK_SCORE_KEY, result.getScore() == null ? 0D : result.getScore());
            document.getMetadata().put(RERANK_RANK_KEY, result.getRank() == null ? rerankedDocuments.size() + 1 : result.getRank());
            document.getMetadata().put(RERANK_MODEL_KEY, "rag-tools");
            rerankedDocuments.add(document);
        }
        log.info("rag-tools rerank 完成: candidateCount={}, topK={}, resultCount={}",
            candidates.size(), topK, rerankedDocuments.size());
        return rerankedDocuments;
    }
}
