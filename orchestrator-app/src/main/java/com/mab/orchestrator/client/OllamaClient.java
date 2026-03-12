package com.mab.orchestrator.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class OllamaClient {

    private final RestTemplate restTemplate;
    private final String ollamaBaseUrl;
    private final String generationModel;
    private final String fallbackModel;
    private final ToolsClient toolsClient;

    public OllamaClient(RestTemplate restTemplate,
                        ToolsClient toolsClient,
                        @Value("${ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
                        @Value("${ollama.generation-model:qwen2.5:7b-instruct}") String generationModel,
                        @Value("${ollama.fallback-model:qwen2.5:3b}") String fallbackModel) {
        this.restTemplate = restTemplate;
        this.toolsClient = toolsClient;
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
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        String active = activeGenerationModel();
        ordered.add(active);
        ordered.add(generationModel.trim());
        if (fallbackModel != null && !fallbackModel.isBlank()) {
            ordered.add(fallbackModel.trim());
        }
        return ordered.stream().filter(model -> !Objects.equals(model, "")).toList();
    }

    private String activeGenerationModel() {
        try {
            var state = toolsClient.systemState();
            if (state != null && state.activeGenerationModel() != null && !state.activeGenerationModel().isBlank()) {
                return state.activeGenerationModel().trim();
            }
        } catch (RuntimeException ignored) {
            // Fall back to deployment default if tools state is unavailable.
        }
        return generationModel.trim();
    }
}
