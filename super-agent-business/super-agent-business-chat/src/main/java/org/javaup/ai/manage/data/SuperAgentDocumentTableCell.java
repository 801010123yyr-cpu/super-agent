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
@TableName("super_agent_document_table_cell")
@EqualsAndHashCode(callSuper = true)
public class SuperAgentDocumentTableCell extends BaseTableData {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long documentId;

    private Long taskId;

    private Long tableId;

    private Long rowId;

    private Long columnId;

    private Integer rowNo;

    private Integer columnNo;

    private String cellText;

    private BigDecimal numericValue;
}
