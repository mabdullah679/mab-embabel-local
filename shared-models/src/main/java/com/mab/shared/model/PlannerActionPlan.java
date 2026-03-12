package com.mab.shared.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record PlannerActionPlan(
        String taskFamily,
        String action,
        String targetEntityType,
        String targetEntityId,
        PlannerLookup targetLookup,
        Map<String, Object> arguments,
        @JsonProperty("needsClarification") boolean needsClarification,
        String clarificationQuestion,
        double confidence
) {
}
