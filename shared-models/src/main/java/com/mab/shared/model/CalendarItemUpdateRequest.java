package com.mab.shared.model;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CalendarItemUpdateRequest(
        @NotBlank String title,
        @NotBlank String date,
        @NotBlank String time,
        List<String> participants,
        @NotBlank String itemType,
        String notes
) {
}
