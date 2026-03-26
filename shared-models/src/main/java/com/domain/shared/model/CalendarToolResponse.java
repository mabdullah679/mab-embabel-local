package com.domain.shared.model;

import java.util.List;

public record CalendarToolResponse(
        String eventId,
        String title,
        String date,
        String time,
        List<String> participants,
        String status,
        String itemType,
        String notes
) {
}

