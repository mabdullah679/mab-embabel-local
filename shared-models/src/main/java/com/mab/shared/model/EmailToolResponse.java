package com.mab.shared.model;

public record EmailToolResponse(
        String id,
        String draft,
        String recipient,
        String subject,
        String body,
        String tone,
        String status,
        String scheduledFor,
        String sentAt
) {
}
