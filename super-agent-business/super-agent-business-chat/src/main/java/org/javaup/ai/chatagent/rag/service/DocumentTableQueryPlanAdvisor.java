package org.javaup.ai.chatagent.rag.service;

import org.javaup.ai.chatagent.rag.model.DocumentTableQueryPlanAdvice;
import org.javaup.ai.manage.model.table.DocumentTableDescriptor;

import java.util.List;
import java.util.Optional;

public interface DocumentTableQueryPlanAdvisor {

    Optional<DocumentTableQueryPlanAdvice> advise(String question, List<DocumentTableDescriptor> tables);
}
