package com.mab.shared.model;

import jakarta.validation.constraints.NotBlank;

public record RagIngestRequest(@NotBlank String content) {
}