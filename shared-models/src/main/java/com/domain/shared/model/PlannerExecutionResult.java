package com.domain.shared.model;

import java.util.List;

public record PlannerExecutionResult(
        String status,
        String message,
        boolean clarificationNeeded,
        String clarificationQuestion,
        String validationError,
        PlannerActionPlan appliedPlan,
        List<String> candidateIds,
        List<String> candidateSummaries
) {
}

