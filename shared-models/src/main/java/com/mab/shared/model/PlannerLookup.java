package com.mab.shared.model;

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
}
