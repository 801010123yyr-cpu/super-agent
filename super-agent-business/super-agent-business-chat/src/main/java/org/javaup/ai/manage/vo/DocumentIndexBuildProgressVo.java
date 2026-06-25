package org.javaup.ai.manage.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 视图对象
 * @author: 阿星不是程序员
 **/

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentIndexBuildProgressVo {

    private Long documentId;

    private Integer indexStatus;

    private String indexStatusName;

    private Long taskId;

    private Integer taskType;

    private String taskTypeName;

    private Integer taskStatus;

    private String taskStatusName;

    private Integer currentStage;

    private String currentStageName;

    private Date startTime;

    private Date finishTime;

    private Long costMillis;

    private Long elapsedMillis;

    private String errorCode;

    private String errorMsg;

    private String extJson;

    private Boolean building;

    private Long latestLogId;

    private Long totalLogCount;

    private List<DocumentTaskLogVo> logs;
}
