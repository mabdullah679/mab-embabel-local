package com.mab.shared.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlannerLookup(
        String referenceText,
        String recipientName,
        String recipientEmail,
        String titleLike,
        String participantName,
        String date,
        String time,
        String itemType,
        String draftReference
) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PlannerLookup fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return new PlannerLookup(value, null, null, null, null, null, null, null, null);
    }
}
