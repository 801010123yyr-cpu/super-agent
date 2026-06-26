package org.javaup.ai.manage.data;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.javaup.database.data.BaseTableData;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("super_agent_kg_canonical_entity_group")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentKgCanonicalEntityGroup extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String scopeKey;

    private String groupKey;

    private String canonicalName;

    private String entityType;

    private Integer entityCount;

    private Integer documentCount;

    private Integer taskCount;

    private BigDecimal rankScore;

    private String metadataJson;
}
