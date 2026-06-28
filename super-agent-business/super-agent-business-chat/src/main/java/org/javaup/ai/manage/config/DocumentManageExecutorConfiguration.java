package org.javaup.ai.manage.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文档管理后台任务线程池。Kafka listener 只负责触发任务，长耗时 RAG 构建在这里执行。
 */
@Configuration
public class DocumentManageExecutorConfiguration {

    @Bean(name = "documentIndexBuildExecutorService", destroyMethod = "shutdown")
    public ExecutorService documentIndexBuildExecutorService(DocumentManageProperties properties) {
        AtomicInteger threadCounter = new AtomicInteger(1);
        DocumentManageProperties.IndexBuild indexBuild = properties.getIndexBuild();
        int poolSize = positiveOrDefault(indexBuild.getExecutorPoolSize(), 2);
        int queueCapacity = positiveOrDefault(indexBuild.getExecutorQueueCapacity(), 32);

        return new ThreadPoolExecutor(
            poolSize,
            poolSize,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("document-index-build-" + threadCounter.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        return value == null || value <= 0 ? defaultValue : value;
    }
}
