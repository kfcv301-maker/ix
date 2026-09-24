package com.admin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/** Bounded, purpose-specific workers keep background work off HTTP threads. */
@Configuration
public class BackgroundExecutorConfig {

    @Bean("flowPauseExecutor")
    public TaskExecutor flowPauseExecutor() {
        return executor("flow-pause-", 2, 4, 256);
    }

    @Bean("forwardSyncExecutor")
    public TaskExecutor forwardSyncExecutor() {
        return executor("forward-sync-", 2, 6, 256);
    }

    @Bean("nodeConfigExecutor")
    public TaskExecutor nodeConfigExecutor() {
        return executor("node-config-", 1, 2, 32);
    }

    /** Coordinates short-lived tunnel diagnostics without holding HTTP threads. */
    @Bean("tunnelDiagnosisCoordinatorExecutor")
    public TaskExecutor tunnelDiagnosisCoordinatorExecutor() {
        return executor("tunnel-diagnosis-", 1, 2, 8);
    }

    /** Bounded probes keep a large tunnel scan from flooding Agent commands. */
    @Bean("tunnelDiagnosisProbeExecutor")
    public TaskExecutor tunnelDiagnosisProbeExecutor() {
        return executor("tunnel-probe-", 2, 4, 32);
    }

    @Bean("vpsDeploymentExecutor")
    public TaskExecutor vpsDeploymentExecutor() {
        return executor("vps-deploy-", 1, 2, 16);
    }

    private TaskExecutor executor(String prefix, int coreSize, int maxSize, int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
