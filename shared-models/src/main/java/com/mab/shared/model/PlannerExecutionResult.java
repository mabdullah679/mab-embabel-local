package com.mab.shared.model;

import java.util.List;

public record PlannerExecutionResult(
        String status,
        String message,
        boolean clarificationNeeded,
        String clarificationQuestion,
        String validationError,
        PlannerActionPlan appliedPlan,
        EmailDraftRecord emailDraft,
        CalendarItemRecord calendarItem,
        List<String> candidateIds,
        List<String> candidateSummaries
) {
}
