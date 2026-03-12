package com.mab.shared.model;

import java.util.List;

public record AgentQueryResponse(String result, List<ToolTrace> traces, int iterations) {
}