package com.mab.orchestrator.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
public class OllamaModelValidator {
    private static final Logger log = LoggerFactory.getLogger(OllamaModelValidator.class);

    @Bean
    ApplicationRunner validateOllamaModels(RestTemplate restTemplate,
                                           @Value("${ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
                                           @Value("${ollama.generation-model}") String generationModel,
                                           @Value("${ollama.fallback-model:}") String fallbackModel) {
        return args -> {
            Set<String> available = fetchModels(restTemplate, ollamaBaseUrl);
            validateDeclaredModel("generation", generationModel, available);
            validateOptionalModel("fallback", fallbackModel, available);
            log.info("Validated Ollama generation models from deployment config. generation={} fallback={}", generationModel, fallbackModel);
        };
    }

    @SuppressWarnings("unchecked")
    private Set<String> fetchModels(RestTemplate restTemplate, String ollamaBaseUrl) {
        Map<String, Object> response = restTemplate.getForObject(ollamaBaseUrl + "/api/tags", Map.class);
        if (response == null || !(response.get("models") instanceof List<?> models)) {
            throw new IllegalStateException("Unable to read Ollama model tags from " + ollamaBaseUrl);
        }
        Set<String> names = new HashSet<>();
        for (Object item : models) {
            if (item instanceof Map<?, ?> modelMap) {
                Object name = modelMap.get("name");
                if (name != null) {
                    names.add(String.valueOf(name));
                }
            }
        }
        return names;
    }

    private void validateDeclaredModel(String label, String model, Set<String> available) {
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("Declared Ollama " + label + " model is missing.");
        }
        if (!matchesAvailableModel(model, available)) {
            throw new IllegalStateException("Declared Ollama " + label + " model is not available: " + model);
        }
    }

    private void validateOptionalModel(String label, String model, Set<String> available) {
        if (model != null && !model.isBlank()) {
            validateDeclaredModel(label, model, available);
        }
    }

    private boolean matchesAvailableModel(String declaredModel, Set<String> available) {
        String normalized = normalizeModelName(declaredModel);
        for (String candidate : available) {
            if (normalizeModelName(candidate).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeModelName(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.endsWith(":latest") ? trimmed.substring(0, trimmed.length() - 7) : trimmed;
    }
}
