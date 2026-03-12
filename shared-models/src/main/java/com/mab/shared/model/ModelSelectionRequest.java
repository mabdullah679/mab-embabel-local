package com.mab.shared.model;

import jakarta.validation.constraints.NotBlank;

public record ModelSelectionRequest(@NotBlank String generationModel) {
}
