package com.domain.shared.model;

import jakarta.validation.constraints.NotBlank;

public record RagRetrievalRequest(@NotBlank String queryText) {
}
