package com.mab.orchestrator.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OllamaClient {

    private final RestTemplate restTemplate;
    private final String ollamaBaseUrl;

    public OllamaClient(RestTemplate restTemplate,
                        @Value("${ollama.base-url:http://localhost:11434}") String ollamaBaseUrl) {
        this.restTemplate = restTemplate;
        this.ollamaBaseUrl = ollamaBaseUrl;
    }

    @SuppressWarnings("unchecked")
    public String summarize(String query, String rawToolResult) {
        try {
            return generate("User query: " + query + "\nTool result: " + rawToolResult + "\nReturn a concise answer.");
        } catch (Exception ignored) {
            return rawToolResult;
        }
    }

    @SuppressWarnings("unchecked")
    public String generate(String prompt) {
        return generate(prompt, false);
    }

    @SuppressWarnings("unchecked")
    public String generateJson(String prompt) {
        return generate(prompt, true);
    }

    @SuppressWarnings("unchecked")
    private String generate(String prompt, boolean json) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", "qwen2.5:7b-instruct");
        payload.put("prompt", prompt);
        payload.put("stream", false);
        if (json) {
            payload.put("format", "json");
        }

        Map<String, Object> response = restTemplate.postForObject(ollamaBaseUrl + "/api/generate", payload, Map.class);
        if (response == null || response.get("response") == null) {
            throw new IllegalStateException("Ollama generation response missing data");
        }

        return String.valueOf(response.get("response"));
    }
}
