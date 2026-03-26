package com.domain.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ToolsApplication {
    private static final Logger log = LoggerFactory.getLogger(ToolsApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ToolsApplication.class, args);
    }

    @Bean
    ApplicationRunner startupLog() {
        return args -> log.info("hello world from {}", ToolsApplication.class.getSimpleName());
    }
}
