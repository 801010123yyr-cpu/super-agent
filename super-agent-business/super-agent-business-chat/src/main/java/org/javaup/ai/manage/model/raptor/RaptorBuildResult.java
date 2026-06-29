package org.javaup.ai.manage.model.raptor;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RaptorBuildResult {

    private int nodeCount;

    private int levelCount;

    private int sourceChunkCount;

    private String inputMode;

    private int inputCount;

    private int reusableSummaryInputCount;

    private int originalChunkInputCount;

    private RaptorQualityReport sourceQualityReport;

    private RaptorQualityReport savedQualityReport;
}
