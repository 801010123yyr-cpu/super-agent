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
@TableName("super_agent_kg_cross_document_community")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentKgCrossDocumentCommunity extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String scopeKey;

    private String communityKey;

    private String title;

    private String summary;

    private String canonicalGroupKeysJson;

    private String relationGroupKeysJson;

    private Integer entityCount;

    private Integer relationGroupCount;

    private Integer evidenceCount;

    private Integer documentCount;

    private BigDecimal rankScore;

    private String metadataJson;
}
