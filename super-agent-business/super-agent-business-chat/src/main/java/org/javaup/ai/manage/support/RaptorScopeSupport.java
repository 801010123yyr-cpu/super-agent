package org.javaup.ai.manage.support;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import org.javaup.ai.manage.data.SuperAgentDocument;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class RaptorScopeSupport {

    public static final String SCOPE_TYPE_DOCUMENT = "DOCUMENT";

    public static final String SCOPE_TYPE_DATASET = "DATASET";

    public static final String GLOBAL_SCOPE_KEY = "global";

    private RaptorScopeSupport() {
    }

    public static String documentScopeKey(Long documentId) {
        return "document:" + documentId;
    }

    public static String knowledgeScopeKey(String knowledgeScopeCode) {
        return "knowledge:" + normalizeScopeCode(knowledgeScopeCode);
    }

    public static List<String> searchScopeKeys(List<SuperAgentDocument> documents) {
        if (CollUtil.isEmpty(documents)) {
            return List.of();
        }
        LinkedHashSet<String> scopeKeys = new LinkedHashSet<>();
        for (SuperAgentDocument document : documents) {
            if (document == null || StrUtil.isBlank(document.getKnowledgeScopeCode())) {
                continue;
            }
            scopeKeys.add(knowledgeScopeKey(document.getKnowledgeScopeCode()));
        }
        scopeKeys.add(GLOBAL_SCOPE_KEY);
        return List.copyOf(scopeKeys);
    }

    public static boolean isDatasetScope(String scopeType) {
        return SCOPE_TYPE_DATASET.equalsIgnoreCase(StrUtil.blankToDefault(scopeType, ""));
    }

    public static String normalizeScopeCode(String knowledgeScopeCode) {
        return StrUtil.blankToDefault(knowledgeScopeCode, "")
            .trim()
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9._-]+", "_");
    }
}
