package org.javaup.ai.manage.model.graph;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GraphRagCrossDocumentIndexBuildResult {

    private String scopeKey;

    private int canonicalGroupCount;

    private int canonicalMemberCount;

    private int relationGroupCount;

    private int relationGroupMemberCount;

    private int communityCount;

    private int communityMemberCount;
}
