package com.mab.shared.model;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record EmailToolRequest(
        String recipient,
        List<String> recipientNames,
        List<String> recipientEmails,
        @NotBlank String subject,
        @NotBlank String body,
        @NotBlank String tone
) {
}
