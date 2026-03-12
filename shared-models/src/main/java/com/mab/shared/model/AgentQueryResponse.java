package com.mab.shared.model;

import java.util.List;

public record AgentQueryResponse(
        String result,
        List<ToolTrace> traces,
        int iterations,
        String status,
        PlannerActionPlan normalizedPlan,
        PlannerExecutionResult executionResult,
        PendingClarification pendingClarification
) {
    public AgentQueryResponse(String result, List<ToolTrace> traces, int iterations) {
        this(result, traces, iterations, null, null, null, null);
    }
}
