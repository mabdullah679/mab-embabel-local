package com.domain.shared.model;

import jakarta.validation.constraints.NotBlank;

public record ModelSelectionRequest(@NotBlank String generationModel) {
}

