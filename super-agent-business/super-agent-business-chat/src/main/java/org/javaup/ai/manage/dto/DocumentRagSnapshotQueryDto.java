package org.javaup.ai.manage.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: RAG 学习快照查询参数
 * @author: 阿星不是程序员
 **/

@Data
public class DocumentRagSnapshotQueryDto {

    @NotNull(message = "文档id不能为空")
    private Long documentId;

    private Long parseTaskId;

    private Long indexTaskId;

    private Long highlightTableId;

    private Integer highlightTableNo;

    private List<Integer> highlightRowNos;

    private List<String> highlightColumnNames;

    private List<String> highlightCellCoordinates;
}
