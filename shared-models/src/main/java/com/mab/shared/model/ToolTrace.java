package com.mab.shared.model;

public record ToolTrace(String tool, String inputSummary, String outputSummary, boolean success, long durationMs) {
}