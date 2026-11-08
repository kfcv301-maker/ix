package com.admin.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import static org.junit.jupiter.api.Assertions.*;

class BackgroundSchedulingConfigurationTest {
    final ApplicationContextRunner runner=new ApplicationContextRunner()
            .withUserConfiguration(BackgroundSchedulingConfiguration.class,Fixture.class);
    @Configuration(proxyBeanMethods=false) static class Fixture {
        @Bean Job job() { return new Job(); }
    }
    static class Job { @Scheduled(initialDelay=60000,fixedDelay=60000) public void tick() { } }
    @Test void normalInstancesRegisterScheduledJobsByDefault() {
        runner.run(context->{
            assertNull(context.getStartupFailure());
            var processor=context.getBean(ScheduledAnnotationBeanPostProcessor.class);
            assertEquals(1,processor.getScheduledTasks().size());
            assertNotSame(context.getBean("taskScheduler"),context.getBean("airportSyncScheduler"));
        });
    }
    @Test void privateWarmupCreatesBeansWithoutRegisteringAnyScheduledJobs() {
        runner.withPropertyValues("panel.background-jobs.enabled=false").run(context->{
            assertNull(context.getStartupFailure());assertNotNull(context.getBean(Job.class));
            assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
            assertFalse(context.containsBean("airportSyncScheduler"));
            assertFalse(context.containsBean("trafficPoolScheduler"));
        });
    }
}
