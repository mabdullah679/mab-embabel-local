package com.mab.shared.model;

public record SystemStateResponse(
        String databaseId,
        String generationModel,
        String activeGenerationModel,
        java.util.List<String> availableGenerationModels,
        String fallbackModel,
        String embeddingModel,
        String modelAuthority
) {
}
