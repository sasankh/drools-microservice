package com.company.drools.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Configuration for thread pools used in rule execution and other async operations.
 * Provides optimized thread pool settings for high-throughput rule processing.
 */
@Configuration
public class ThreadPoolConfig {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolConfig.class);

    // Rule execution thread pool configuration
    @Value("${drools.thread-pool.rule-execution.core-size:10}")
    private int ruleExecutionCorePoolSize;

    @Value("${drools.thread-pool.rule-execution.max-size:50}")
    private int ruleExecutionMaxPoolSize;

    @Value("${drools.thread-pool.rule-execution.queue-capacity:100}")
    private int ruleExecutionQueueCapacity;

    @Value("${drools.thread-pool.rule-execution.keep-alive:60}")
    private int ruleExecutionKeepAliveSeconds;

    // Storage operation thread pool configuration
    @Value("${drools.thread-pool.storage.core-size:5}")
    private int storageCorePoolSize;

    @Value("${drools.thread-pool.storage.max-size:20}")
    private int storageMaxPoolSize;

    @Value("${drools.thread-pool.storage.queue-capacity:50}")
    private int storageQueueCapacity;

    @Value("${drools.thread-pool.storage.keep-alive:60}")
    private int storageKeepAliveSeconds;

    /**
     * Thread pool executor for rule execution operations.
     * Optimized for CPU-intensive rule processing with configurable concurrency.
     */
    @Bean("ruleExecutionExecutor")
    public Executor ruleExecutionExecutor() {
        log.info("Configuring rule execution thread pool: core={}, max={}, queue={}, keep-alive={}s",
                ruleExecutionCorePoolSize, ruleExecutionMaxPoolSize, 
                ruleExecutionQueueCapacity, ruleExecutionKeepAliveSeconds);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // Core configuration
        executor.setCorePoolSize(ruleExecutionCorePoolSize);
        executor.setMaxPoolSize(ruleExecutionMaxPoolSize);
        executor.setQueueCapacity(ruleExecutionQueueCapacity);
        executor.setKeepAliveSeconds(ruleExecutionKeepAliveSeconds);
        
        // Thread naming and lifecycle
        executor.setThreadNamePrefix("rule-exec-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        
        // Rejection policy - caller runs to provide backpressure
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // Allow core threads to timeout when idle
        executor.setAllowCoreThreadTimeOut(true);
        
        executor.initialize();
        
        log.info("Rule execution thread pool initialized successfully");
        return executor;
    }

    /**
     * Thread pool executor for storage operations (S3, file I/O).
     * Optimized for I/O-bound operations with separate pool to prevent blocking rule execution.
     */
    @Bean("storageExecutor")
    public Executor storageExecutor() {
        log.info("Configuring storage operations thread pool: core={}, max={}, queue={}, keep-alive={}s",
                storageCorePoolSize, storageMaxPoolSize, 
                storageQueueCapacity, storageKeepAliveSeconds);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        
        // Core configuration
        executor.setCorePoolSize(storageCorePoolSize);
        executor.setMaxPoolSize(storageMaxPoolSize);
        executor.setQueueCapacity(storageQueueCapacity);
        executor.setKeepAliveSeconds(storageKeepAliveSeconds);
        
        // Thread naming and lifecycle
        executor.setThreadNamePrefix("storage-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        
        // Rejection policy - caller runs to provide backpressure
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        
        // Allow core threads to timeout when idle
        executor.setAllowCoreThreadTimeOut(true);
        
        executor.initialize();
        
        log.info("Storage operations thread pool initialized successfully");
        return executor;
    }

    /**
     * Get current rule execution thread pool statistics for monitoring.
     */
    public String getRuleExecutionPoolStats() {
        Executor executor = ruleExecutionExecutor();
        if (executor instanceof ThreadPoolTaskExecutor) {
            ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;
            ThreadPoolExecutor threadPoolExecutor = taskExecutor.getThreadPoolExecutor();
            
            return String.format(
                "RuleExecutionPool[active=%d, pool=%d/%d, queue=%d/%d, completed=%d]",
                threadPoolExecutor.getActiveCount(),
                threadPoolExecutor.getPoolSize(),
                threadPoolExecutor.getMaximumPoolSize(),
                threadPoolExecutor.getQueue().size(),
                ruleExecutionQueueCapacity,
                threadPoolExecutor.getCompletedTaskCount()
            );
        }
        return "RuleExecutionPool[stats unavailable]";
    }

    /**
     * Get current storage thread pool statistics for monitoring.
     */
    public String getStoragePoolStats() {
        Executor executor = storageExecutor();
        if (executor instanceof ThreadPoolTaskExecutor) {
            ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) executor;
            ThreadPoolExecutor threadPoolExecutor = taskExecutor.getThreadPoolExecutor();
            
            return String.format(
                "StoragePool[active=%d, pool=%d/%d, queue=%d/%d, completed=%d]",
                threadPoolExecutor.getActiveCount(),
                threadPoolExecutor.getPoolSize(),
                threadPoolExecutor.getMaximumPoolSize(),
                threadPoolExecutor.getQueue().size(),
                storageQueueCapacity,
                threadPoolExecutor.getCompletedTaskCount()
            );
        }
        return "StoragePool[stats unavailable]";
    }
}