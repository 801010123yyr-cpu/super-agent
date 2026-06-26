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
@TableName("super_agent_kg_canonical_entity_member")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentKgCanonicalEntityMember extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String scopeKey;

    private Long groupId;

    private String groupKey;

    private Long entityId;

    private Long documentId;

    private Long taskId;

    private String entityName;

    private String normalizedName;

    private String entityType;

    private String metadataJson;
}
