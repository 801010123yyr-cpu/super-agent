package org.javaup.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_knowledge_base")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentKnowledgeBase extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String baseCode;

    private String baseName;

    private String description;

    private String embeddingModel;

    private String retrievalConfigJson;

    private String graphRagConfigJson;

    private String raptorConfigJson;

    private String metadataFilterJson;

    private Integer isDefault;

    private Integer sortOrder;
}
