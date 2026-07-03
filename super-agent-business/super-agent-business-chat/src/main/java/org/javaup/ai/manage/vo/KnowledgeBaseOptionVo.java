package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeBaseOptionVo {

    private String id;

    private String baseCode;

    private String baseName;

    private String description;

    private String isDefault;

    private String retrievableDocumentCount;
}
