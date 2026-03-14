package com.mab.tools.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mab.shared.model.CalendarItemRecord;
import com.mab.shared.model.EmailDraftRecord;
import com.mab.shared.model.RecordLookupRequest;
import com.mab.tools.repository.ToolRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolsServiceTest {

    private final ToolsService service = new ToolsService(
            null,
            null,
            new ObjectMapper(),
            "http://localhost:11434",
            "qwen2.5:7b-instruct",
            "qwen2.5:3b",
            "nomic-embed-text"
    );

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

    @Test
    void recordLookupReturnsLatestReminderForDate() {
        ToolRepository repository = mock(ToolRepository.class);
        when(repository.listCalendarItems()).thenReturn(List.of(
                new CalendarItemRecord("r1", "Pay rent", "2026-03-11", "08:00", List.of(), "CREATED", "REMINDER", "", "2026-03-10T10:00:00Z"),
                new CalendarItemRecord("r2", "Send invoice", "2026-03-11", "17:30", List.of(), "CREATED", "REMINDER", "Client A", "2026-03-10T11:00:00Z")
        ));
        when(repository.listEmailDrafts()).thenReturn(List.of());

        ToolsService lookupService = new ToolsService(
                repository,
                null,
                new ObjectMapper(),
                "http://localhost:11434",
                "qwen2.5:7b-instruct",
                "qwen2.5:3b",
                "nomic-embed-text"
        );

        var result = lookupService.lookupRecords(new RecordLookupRequest(
                "what was the last reminder for 2026-03-11?",
                "calendar_item",
                "REMINDER",
                null,
                "2026-03-11",
                null,
                "time",
                "desc",
                1,
                true
        ));

        assertEquals(2, result.totalMatches());
        assertEquals("r2", result.primaryMatch().recordId());
        assertEquals("Send invoice", result.primaryMatch().title());
        assertEquals("Client A", result.primaryMatch().notes());
    }

    @Test
    void recordLookupPrefersExplicitEventIdOverOtherFilters() {
        ToolRepository repository = mock(ToolRepository.class);
        when(repository.listCalendarItems()).thenReturn(List.of(
                new CalendarItemRecord("cb06dc0b-6358-445b-964e-67a69e20dbac", "Push code to origin main", "2026-03-13", "22:00", List.of(), "CREATED", "REMINDER", "", "2026-03-12T21:00:00Z")
        ));
        when(repository.listEmailDrafts()).thenReturn(List.of());

        ToolsService lookupService = new ToolsService(
                repository,
                null,
                new ObjectMapper(),
                "http://localhost:11434",
                "qwen2.5:7b-instruct",
                "qwen2.5:3b",
                "nomic-embed-text"
        );

        var result = lookupService.lookupRecords(new RecordLookupRequest(
                "what date and time is Event ID: cb06dc0b-6358-445b-964e-67a69e20dbac scheduled for?",
                "calendar_item",
                "REMINDER",
                "cb06dc0b-6358-445b-964e-67a69e20dbac",
                "2026-03-13",
                "some unrelated title text",
                "time",
                "desc",
                1,
                true
        ));

        assertEquals(1, result.totalMatches());
        assertEquals("cb06dc0b-6358-445b-964e-67a69e20dbac", result.primaryMatch().recordId());
        assertEquals("2026-03-13", result.primaryMatch().date());
        assertEquals("22:00", result.primaryMatch().time());
    }
}
