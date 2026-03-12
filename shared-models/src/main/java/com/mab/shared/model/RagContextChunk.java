package com.mab.shared.model;

public record RagContextChunk(
        String chunkId,
        String sourceDocumentId,
        String sourceLabel,
        int chunkIndex,
        String content,
        double finalScore,
        String inclusionReason
) {
}
