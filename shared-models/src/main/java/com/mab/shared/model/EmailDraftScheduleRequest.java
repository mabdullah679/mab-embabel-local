package com.mab.shared.model;

import jakarta.validation.constraints.NotBlank;

public record EmailDraftScheduleRequest(@NotBlank String scheduledFor) {
}
