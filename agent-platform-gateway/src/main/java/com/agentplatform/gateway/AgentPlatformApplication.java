package com.agentplatform.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.agentplatform")
@EntityScan(basePackages = {
    "com.agentplatform.common.model",
    "com.agentplatform.gateway.llm.model",
    "com.agentplatform.gateway.memory.model",
    "com.agentplatform.gateway.planner.model",
    "com.agentplatform.gateway.workflow.model"
})
@EnableJpaRepositories(basePackages = {
    "com.agentplatform.common.repository",
    "com.agentplatform.gateway.llm.repository",
    "com.agentplatform.gateway.memory.repository",
    "com.agentplatform.gateway.planner.repository",
    "com.agentplatform.gateway.workflow.repository"
})
@EnableCaching
@EnableAsync
@EnableScheduling
public class AgentPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentPlatformApplication.class, args);
    }
}
