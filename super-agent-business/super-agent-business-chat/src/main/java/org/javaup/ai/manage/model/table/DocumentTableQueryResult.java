package org.javaup.ai.manage.model.table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTableQueryResult {

    private Long tableId;

    private Long documentId;

    private Long taskId;

    private Long blockId;

    private Integer tableNo;

    private String tableTitle;

    private String sectionPath;

    private Integer pageNo;

    private String pageRange;

    private String bboxJson;

    private String operation;

    private BigDecimal value;

    @Builder.Default
    private Map<String, BigDecimal> groupedValues = new LinkedHashMap<>();

    private int matchedRowCount;

    private String evidenceText;
}
