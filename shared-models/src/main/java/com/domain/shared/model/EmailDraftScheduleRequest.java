package com.domain.shared.model;

import jakarta.validation.constraints.NotBlank;

public record EmailDraftScheduleRequest(@NotBlank String scheduledFor) {
}

