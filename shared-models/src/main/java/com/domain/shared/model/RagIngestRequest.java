package com.domain.shared.model;

import jakarta.validation.constraints.NotBlank;

public record RagIngestRequest(@NotBlank String content) {
}
