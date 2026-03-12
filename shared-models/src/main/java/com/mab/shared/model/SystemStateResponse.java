package com.mab.shared.model;

public record SystemStateResponse(
        String databaseId,
        String generationModel,
        String fallbackModel,
        String embeddingModel,
        String modelAuthority
) {
}
