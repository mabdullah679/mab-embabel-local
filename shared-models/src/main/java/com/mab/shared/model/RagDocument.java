package com.mab.shared.model;

public record RagDocument(
        String id,
        String content,
        double score,
        String sourceDocumentId,
        String sourceLabel,
        Integer chunkIndex
) {
    public RagDocument(String id, String content, double score) {
        this(id, content, score, null, null, null);
    }
}
