package com.mab.shared.model;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record AgentQueryRequest(
        @NotBlank String query,
        List<ConversationTurn> history,
        PendingClarification pendingClarification
) {
}
