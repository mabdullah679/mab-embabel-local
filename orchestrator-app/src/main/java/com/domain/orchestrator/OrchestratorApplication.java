package com.domain.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class OrchestratorApplication {
    private static final Logger log = LoggerFactory.getLogger(OrchestratorApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorApplication.class, args);
    }

    @Bean
    ApplicationRunner startupLog() {
        return args -> log.info("hello world from {}", OrchestratorApplication.class.getSimpleName());
    }
}
