package org.javaup.ai.chatagent.rag.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.javaup.ai.manage.model.table.DocumentTableDescriptor;
import org.javaup.ai.manage.model.table.DocumentTableQuery;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTableQueryPlan {

    private DocumentTableDescriptor table;

    private DocumentTableQuery query;

    private String reason;
}
