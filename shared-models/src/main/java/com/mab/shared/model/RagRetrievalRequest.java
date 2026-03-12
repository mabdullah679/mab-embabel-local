package com.mab.shared.model;

import jakarta.validation.constraints.NotBlank;

public record RagRetrievalRequest(@NotBlank String queryText) {
}