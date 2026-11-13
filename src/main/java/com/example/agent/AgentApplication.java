package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Spring Boot composition root; the first enterprise deployment remains a headless CLI/worker process. */
@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
@EnableScheduling
public class AgentApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AgentApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }
}
