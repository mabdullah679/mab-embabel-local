package com.mab.shared.model;

public record EmailDraftRecord(
        String id,
        String recipient,
        String subject,
        String body,
        String tone,
        String status,
        String scheduledFor,
        String sentAt,
        String createdAt,
        String updatedAt
) {
}
