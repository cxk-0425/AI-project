package com.kb.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步配置
 * <p>
 * 为 RAG 检索和其他异步任务配置自定义线程池。
 */
@Slf4j
@Configuration
@EnableAsync
public class AsyncConfig {

    @Value("${kb.async.core-pool-size:4}")
    private int corePoolSize;

    @Value("${kb.async.max-pool-size:16}")
    private int maxPoolSize;

    @Value("${kb.async.queue-capacity:100}")
    private int queueCapacity;

    @Value("${kb.async.keep-alive-seconds:60}")
    private int keepAliveSeconds;

    /**
     * RAG 检索专用线程池
     * <p>
     * 用于并发执行上下文检索和来源文档查询。
     */
    @Bean("ragExecutor")
    public Executor ragExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 核心线程数（CPU 密集型任务：CPU 核心数；I/O 密集型：CPU 核心数 * 2）
        executor.setCorePoolSize(corePoolSize);

        // 最大线程数
        executor.setMaxPoolSize(maxPoolSize);

        // 队列容量
        executor.setQueueCapacity(queueCapacity);

        // 线程空闲时间
        executor.setKeepAliveSeconds(keepAliveSeconds);

        // 线程名称前缀
        executor.setThreadNamePrefix("rag-async-");

        // 拒绝策略：调用者运行（降级到主线程执行）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 等待所有任务完成后关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();

        log.info("[AsyncConfig] RAG 线程池初始化完成 - Core: {}, Max: {}, Queue: {}",
                corePoolSize, maxPoolSize, queueCapacity);

        return executor;
    }
}
