package com.mab.shared.model;

public record RagIngestResponse(String id, String status, String sourceDocumentId, int chunkCount) {
    public RagIngestResponse(String id, String status) {
        this(id, status, id, 0);
    }
}
