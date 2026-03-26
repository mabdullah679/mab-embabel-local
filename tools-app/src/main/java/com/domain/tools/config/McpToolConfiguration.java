package com.domain.tools.config;

import com.domain.tools.mcp.AgentTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfiguration {

    @Bean
    ToolCallbackProvider toolCallbackProvider(AgentTools agentTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(agentTools)
                .build();
    }
}

