package com.mab.shared.model;

import java.util.List;

public record PendingClarification(
        String taskFamily,
        String action,
        String question,
        PlannerActionPlan plan,
        List<String> candidateIds,
        List<String> candidateSummaries
) {
}
