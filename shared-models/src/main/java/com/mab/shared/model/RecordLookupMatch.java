package com.mab.shared.model;

public record RecordLookupMatch(
        String recordType,
        String recordId,
        String title,
        String subject,
        String content,
        String date,
        String time,
        String status,
        String itemType,
        String recipient,
        String senderName,
        String notes,
        String createdAt,
        String updatedAt
) {
}
