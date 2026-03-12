package com.mab.orchestrator.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OllamaClient {

    private final RestTemplate restTemplate;
    private final String ollamaBaseUrl;
    private final String generationModel;
    private final String fallbackModel;

    public OllamaClient(RestTemplate restTemplate,
                        @Value("${ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
                        @Value("${ollama.generation-model:qwen2.5:7b-instruct}") String generationModel,
                        @Value("${ollama.fallback-model:qwen2.5:3b}") String fallbackModel) {
        this.restTemplate = restTemplate;
        this.ollamaBaseUrl = ollamaBaseUrl;
        this.generationModel = generationModel;
        this.fallbackModel = fallbackModel;
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

    public String selectContextMode(String query, String historyPreview) {
        String prompt = """
                Return strict JSON only with key mode.
                Allowed values: current_only, recent_turns, full_history.
                Choose the smallest context window that still safely resolves references.
                Prefer current_only unless prior turns are clearly required.
                User request: %s
                Available history preview:
                %s
                """.formatted(query, historyPreview);
        return generate(prompt, true);
    }

    @SuppressWarnings("unchecked")
    private String generate(String prompt, boolean json) {
        RuntimeException lastFailure = null;
        for (String model : configuredModels()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("prompt", prompt);
            payload.put("stream", false);
            if (json) {
                payload.put("format", "json");
            }

            try {
                Map<String, Object> response = restTemplate.postForObject(ollamaBaseUrl + "/api/generate", payload, Map.class);
                if (response == null || response.get("response") == null) {
                    throw new IllegalStateException("Ollama generation response missing data");
                }
                return String.valueOf(response.get("response"));
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new IllegalStateException("No Ollama generation model configured");
    }

    private List<String> configuredModels() {
        if (generationModel == null || generationModel.isBlank()) {
            throw new IllegalStateException("ollama.generation-model must be configured by deployment");
        }
        if (fallbackModel == null || fallbackModel.isBlank() || generationModel.trim().equals(fallbackModel.trim())) {
            return List.of(generationModel.trim());
        }
        return List.of(generationModel.trim(), fallbackModel.trim());
    }
}
