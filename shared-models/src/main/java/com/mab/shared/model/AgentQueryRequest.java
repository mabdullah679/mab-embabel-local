package com.mab.shared.model;

import jakarta.validation.constraints.NotBlank;

public record AgentQueryRequest(@NotBlank String query) {
}