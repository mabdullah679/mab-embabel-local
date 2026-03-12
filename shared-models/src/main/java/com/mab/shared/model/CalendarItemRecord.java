package com.mab.shared.model;

import java.util.List;

public record CalendarItemRecord(
        String id,
        String title,
        String date,
        String time,
        List<String> participants,
        String status,
        String itemType,
        String notes,
        String createdAt
) {
}
