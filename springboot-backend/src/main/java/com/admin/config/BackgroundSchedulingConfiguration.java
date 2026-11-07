package com.admin.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Private warm-up instances can opt out of every scheduled job. Normal installs remain enabled. */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="panel.background-jobs.enabled",havingValue="true",matchIfMissing=true)
public class BackgroundSchedulingConfiguration {
    @Bean(name="taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() { return scheduler("panel-scheduled-"); }
    @Bean(name="airportSyncScheduler")
    public ThreadPoolTaskScheduler airportSyncScheduler() { return scheduler("airport-scheduled-"); }
    private static ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);scheduler.setThreadNamePrefix(prefix);scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
