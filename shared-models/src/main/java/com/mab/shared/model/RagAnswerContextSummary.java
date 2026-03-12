package com.mab.shared.model;

import java.util.List;

public record RagAnswerContextSummary(
        String query,
        String summary,
        List<String> sourceLabels,
        int chunkCount,
        int characterCount
) {
}
