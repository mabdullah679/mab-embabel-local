package com.mab.shared.model;

import java.util.List;

public record RagRetrievalResponse(
        String query,
        List<RagChunkCandidate> denseCandidates,
        List<RagChunkCandidate> lexicalCandidates,
        List<RagChunkCandidate> fusedCandidates,
        List<RagChunkCandidate> rerankedCandidates,
        List<RagContextChunk> contextChunks,
        RagAnswerContextSummary answerContext,
        List<RagDocument> documents
) {
}
