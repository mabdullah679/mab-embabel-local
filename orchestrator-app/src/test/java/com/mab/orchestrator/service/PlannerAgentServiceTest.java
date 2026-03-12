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

    @Test
    void routesBareSenderReplyBackToEmailFlow() {
        String family = service.detectTaskFamily(
                "John Doe.",
                List.of(
                        new com.mab.shared.model.ConversationTurn("user", "draft an email to Joe about tomorrow's check-in"),
                        new com.mab.shared.model.ConversationTurn("assistant", "Who should the sender be for that email?")
                )
        );
        assertEquals("email", family);
    }

    @Test
    void routesTitleAndDateFollowUpBackToCalendarFlow() {
        String family = service.detectTaskFamily(
                "the one titled \"Push code to origin main\" for today 12 pm.",
                List.of(
                        new com.mab.shared.model.ConversationTurn("user", "change the task type from meeting to reminder"),
                        new com.mab.shared.model.ConversationTurn("assistant", "I found multiple matching calendar items. Please specify which one to update.")
                )
        );
        assertEquals("calendar", family);
    }

    @Test
    void routesDraftIdEditRequestToEmailInsteadOfMetadata() {
        String family = service.detectTaskFamily("edit draft id 8548414f-4905-4a3d-8674-b60a053cd8f9 to remove the meeting context", List.of());
        assertEquals("email", family);
    }

    @Test
    void routesEventIdUpdateRequestToCalendarInsteadOfMetadata() {
        String family = service.detectTaskFamily("update Event ID: 75d4660b-b836-417b-9e52-fde36036f66f from a meeting event to a reminder.", List.of());
        assertEquals("calendar", family);
    }

    @Test
    void routesDraftSchedulingRequestToEmailInsteadOfClarification() {
        String family = service.detectTaskFamily("schedule Draft ID: a647cf48-e56a-4bfc-b4f6-34dcfbd34ee1 to be sent later today at 4pm", List.of());
        assertEquals("email", family);
    }

    @Test
    void routesTimeOnlyEmailFollowUpBackToEmailFlow() {
        String family = service.detectTaskFamily(
                "4pm today.",
                List.of(
                        new com.mab.shared.model.ConversationTurn("user", "schedule Draft ID: a647cf48-e56a-4bfc-b4f6-34dcfbd34ee1 to be sent later today at 4pm"),
                        new com.mab.shared.model.ConversationTurn("assistant", "What time should I schedule that email for?")
                )
        );
        assertEquals("email", family);
    }
}
