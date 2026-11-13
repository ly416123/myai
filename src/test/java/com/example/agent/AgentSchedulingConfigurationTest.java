package com.example.agent;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Instant;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSchedulingConfigurationTest {
    @Test
    void applicationSchedulerUsesDaemonThreadsSoFiniteCliModesCanExit() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(SchedulingTestApplication.class)) {
            var scheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            var daemonThread = new CompletableFuture<Boolean>();
            scheduler.schedule(() -> daemonThread.complete(Thread.currentThread().isDaemon()),
                    Instant.now().plusMillis(1));

            assertTrue(daemonThread.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void jvmExitsWhenOnlyTheApplicationSchedulerRemains() throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                SchedulerOnlyProcess.class.getName()).redirectErrorStream(true).start();
        boolean exited = process.waitFor(5, TimeUnit.SECONDS);
        if (!exited) process.destroyForcibly().waitFor();

        assertTrue(exited, "a scheduled maintenance thread must not keep a finite CLI JVM alive");
        assertEquals(0, process.exitValue());
    }

    public static final class SchedulerOnlyProcess {
        public static void main(String[] args) {
            var context = new AnnotationConfigApplicationContext(SchedulingTestApplication.class);
            context.getBean("taskScheduler", ThreadPoolTaskScheduler.class)
                    .schedule(() -> { }, Instant.now().plusSeconds(30));
            // Intentionally leave the context open: finite command modes return from main here.
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @Import(AgentSchedulingConfiguration.class)
    static class SchedulingTestApplication { }
}
