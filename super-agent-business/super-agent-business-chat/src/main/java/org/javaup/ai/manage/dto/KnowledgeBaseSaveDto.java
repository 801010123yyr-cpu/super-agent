package org.javaup.ai.manage.dto;

import lombok.Data;

@Data
public class KnowledgeBaseSaveDto {

    private String id;

    private String baseCode;

    private String baseName;

    private String description;

    private String embeddingModel;

    private String retrievalConfigJson;

    private String graphRagConfigJson;

    private String raptorConfigJson;

    private String metadataFilterJson;

    private String isDefault;

    private String sortOrder;

    private String operatorId;
}
