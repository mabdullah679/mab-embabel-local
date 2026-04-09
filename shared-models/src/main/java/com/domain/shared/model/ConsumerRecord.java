package com.domain.shared.model;

public record ConsumerRecord(
        Long id,
        String sourceName,
        String storedValue,
        Integer lastMessageNumber,
        String lastMessageText,
        String lastMergedAt,
        String syncStatus
) {
}
