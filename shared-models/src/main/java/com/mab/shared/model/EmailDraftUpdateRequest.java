package com.mab.shared.model;

import jakarta.validation.constraints.NotBlank;

public record EmailDraftUpdateRequest(
        @NotBlank String recipient,
        @NotBlank String subject,
        @NotBlank String body,
        @NotBlank String tone
) {
}
