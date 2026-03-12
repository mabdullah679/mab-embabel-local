package com.mab.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlannerAgentServiceTest {

    private final PlannerAgentService service = new PlannerAgentService(null, null, new ObjectMapper());

    @Test
    void prefersEmailForEmailDraftWithMeetingDetails() {
        String family = service.detectTaskFamily("draft an email to joe notifying him we have a meeting at 2pm with alex.");
        assertEquals("email", family);
    }

    @Test
    void returnsClarificationForMixedEmailAndCalendarWithoutClearAction() {
        String family = service.detectTaskFamily("email and calendar for joe and alex tomorrow");
        assertEquals("clarification", family);
    }

    @Test
    void routesDocumentQuestionToRag() {
        String family = service.detectTaskFamily("what do the docs say about planner agents?");
        assertEquals("rag", family);
    }

    @Test
    void routesGreetingToChat() {
        String family = service.detectTaskFamily("hi");
        assertEquals("chat", family);
    }

    @Test
    void routesCapabilityQuestionToChat() {
        String family = service.detectTaskFamily("what can you do?");
        assertEquals("chat", family);
    }

    @Test
    void routesNaturalEventPromptToCalendar() {
        String family = service.detectTaskFamily("lunch at 3pm with alex");
        assertEquals("calendar", family);
    }

    @Test
    void routesCalendarClarificationFollowUpUsingRecentHistory() {
        String family = service.detectTaskFamily(
                "the one titled \"push code to origin main\"",
                List.of(
                        new com.mab.shared.model.ConversationTurn("user", "add the task push code to origin main tomorrow at 12pm"),
                        new com.mab.shared.model.ConversationTurn("assistant", "Task added: Push code to Origin Main on 12pm tomorrow."),
                        new com.mab.shared.model.ConversationTurn("user", "change the task type from meeting to reminder"),
                        new com.mab.shared.model.ConversationTurn("assistant", "I couldn't identify which calendar item to update.")
                )
        );
        assertEquals("calendar", family);
    }
}
