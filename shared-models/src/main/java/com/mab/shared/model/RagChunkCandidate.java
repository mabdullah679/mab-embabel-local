package com.mab.shared.model;

public record RagChunkCandidate(
        String chunkId,
        String sourceDocumentId,
        String sourceLabel,
        int chunkIndex,
        String content,
        Double denseScore,
        Double lexicalScore,
        Double fusedScore,
        Double rerankScore,
        Double finalScore,
        Integer denseRank,
        Integer lexicalRank,
        Integer fusedRank,
        Integer rerankRank
) {
}
