package com.domain.shared.model;

import jakarta.validation.constraints.NotBlank;

public record ConversationTurn(
        @NotBlank String role,
        @NotBlank String content
) {
}

