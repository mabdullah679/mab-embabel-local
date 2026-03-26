package com.domain.shared.model;

import jakarta.validation.constraints.NotBlank;

public record EmailDraftUpdateRequest(
        @NotBlank String recipient,
        String senderName,
        @NotBlank String subject,
        @NotBlank String body,
        @NotBlank String tone
) {
}

