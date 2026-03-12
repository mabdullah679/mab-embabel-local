package com.mab.tools.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolsServiceTest {

    private final ToolsService service = new ToolsService(null, null, new ObjectMapper(), "http://localhost:11434");

    @Test
    void chunkingKeepsShortDocumentsWhole() {
        List<String> chunks = service.chunkContent("Short document for retrieval.");

        assertEquals(List.of("Short document for retrieval."), chunks);
    }

    @Test
    void chunkingIsDeterministicForLongDocuments() {
        String content = ("Spring MCP tools are orchestrated by a planner agent. "
                + "Hybrid retrieval combines dense and lexical search. ").repeat(16);

        List<String> first = service.chunkContent(content);
        List<String> second = service.chunkContent(content);

        assertEquals(first, second);
        assertTrue(first.size() > 1);
    }

    @Test
    void chunkingNormalizesWhitespaceAndPreservesOverlap() {
        String content = ("alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau "
                + "upsilon phi chi psi omega ").repeat(20);

        List<String> chunks = service.chunkContent("  " + content.replace("  ", "   ") + "\n\n");

        assertTrue(chunks.size() > 1);
        assertFalse(chunks.getFirst().startsWith(" "));
        assertFalse(chunks.getLast().endsWith(" "));
        String tail = chunks.getFirst().substring(Math.max(0, chunks.getFirst().length() - 40));
        assertTrue(chunks.get(1).contains(tail.substring(0, Math.min(20, tail.length()))));
    }
}
