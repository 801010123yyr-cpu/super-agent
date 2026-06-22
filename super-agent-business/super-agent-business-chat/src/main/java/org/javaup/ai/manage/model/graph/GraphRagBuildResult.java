package org.javaup.ai.manage.model.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GraphRagBuildResult {

    private int entityCount;

    private int relationCount;

    private int evidenceCount;

    private int communityCount;
}
