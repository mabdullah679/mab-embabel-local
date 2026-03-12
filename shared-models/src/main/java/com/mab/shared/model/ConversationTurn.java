package com.mab.shared.model;

import jakarta.validation.constraints.NotBlank;

public record ConversationTurn(
        @NotBlank String role,
        @NotBlank String content
) {
}
