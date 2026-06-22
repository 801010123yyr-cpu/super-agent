package org.javaup.ai.manage.service;

import org.javaup.ai.manage.dto.DocumentRagSnapshotQueryDto;
import org.javaup.ai.manage.vo.DocumentRagSnapshotVo;

public interface DocumentRagSnapshotService {

    DocumentRagSnapshotVo querySnapshot(DocumentRagSnapshotQueryDto dto);
}
