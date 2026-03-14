package com.mab.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mab.orchestrator.client.OllamaClient;
import com.mab.orchestrator.client.ToolsClient;
import com.mab.shared.model.AgentQueryRequest;
import com.mab.shared.model.RecordLookupMatch;
import com.mab.shared.model.RecordLookupResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void routesLastReminderQuestionToRecords() {
        String family = service.detectTaskFamily("what was the last reminder for march 11, 2026?");
        assertEquals("records", family);
    }

    @Test
    void routesReminderUuidDetailQuestionToRecords() {
        String family = service.detectTaskFamily("tell me more about reminder uuid 75d4660b-b836-417b-9e52-fde36036f66f");
        assertEquals("records", family);
    }

    @Test
    void routesEmailDraftLookupQuestionToRecords() {
        String family = service.detectTaskFamily("What email drafts do I have for Joe?");
        assertEquals("records", family);
    }

    @Test
    void routesGenericDraftedEmailsQuestionToRecords() {
        String family = service.detectTaskFamily("What emails do I have drafted?");
        assertEquals("records", family);
    }

    @Test
    void categorizesDraftIdReadAndMutationRequestsSeparately() {
        assertEquals("records", service.detectTaskFamily("who are the current recipients for Draft ID: 0758653c-00b8-429c-b847-9d5b40d78dad?"));
        assertEquals("email", service.detectTaskFamily("add alex@example.local to Draft ID: 0758653c-00b8-429c-b847-9d5b40d78dad"));
    }

    @Test
    void categorizesEventIdReadAndMutationRequestsSeparately() {
        assertEquals("records", service.detectTaskFamily("what time is Event ID: 6d9f32b5-a03b-46f8-b482-3cd91b4b6610 scheduled for?"));
        assertEquals("calendar", service.detectTaskFamily("move Event ID: 6d9f32b5-a03b-46f8-b482-3cd91b4b6610 to friday 10pm"));
    }

    @Test
    void keepsCheckAgainInsideRecordsFlow() {
        String family = service.detectTaskFamily(
                "can you check again?",
                List.of(
                        new com.mab.shared.model.ConversationTurn("user", "what date and time is Event ID: cb06dc0b-6358-445b-964e-67a69e20dbac scheduled for?"),
                        new com.mab.shared.model.ConversationTurn("assistant", "I couldn't find a matching persisted record for that request.")
                )
        );
        assertEquals("records", family);
    }

    @Test
    void executesRecordLookupWhenPlannerReturnsQueryRecordsActionUnderEmailFamily() {
        ToolsClient toolsClient = mock(ToolsClient.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        PlannerAgentService routedService = new PlannerAgentService(toolsClient, ollamaClient, new ObjectMapper());

        when(ollamaClient.generateJson(any())).thenReturn("""
                {
                  "taskFamily":"email",
                  "action":"query_records",
                  "targetEntityType":"email_draft",
                  "arguments":{"recordType":"email_draft","referenceText":"Joe","includeDetails":true}
                }
                """);
        when(toolsClient.lookupRecords(any())).thenReturn(new RecordLookupResponse(
                "What email drafts do I have for Joe?",
                "email_draft",
                null,
                null,
                null,
                "updated_at",
                "desc",
                true,
                1,
                new RecordLookupMatch("email_draft", "draft-1", null, "Hello Joe", "Draft body", null, null, "DRAFT", null, "joe@example.local", "Sam", null, "2026-03-12T18:00:00Z", "2026-03-12T18:05:00Z"),
                List.of(new RecordLookupMatch("email_draft", "draft-1", null, "Hello Joe", "Draft body", null, null, "DRAFT", null, "joe@example.local", "Sam", null, "2026-03-12T18:00:00Z", "2026-03-12T18:05:00Z"))
        ));

        var response = routedService.execute(new AgentQueryRequest("What email drafts do I have for Joe?", List.of(), null));

        assertEquals("COMPLETED", response.status());
        assertTrue(response.result().contains("Hello Joe"));
        assertTrue(response.traces().stream().anyMatch(trace -> "RecordLookupTool".equals(trace.tool())));
    }

    @Test
    void routesEventIdMoveFollowUpToCalendar() {
        String family = service.detectTaskFamily(
                "sorry, can you move Event ID: 6d9f32b5-a03b-46f8-b482-3cd91b4b6610 to friday 1pm instead?",
                List.of(
                        new com.mab.shared.model.ConversationTurn("user", "can you change the reminder for Event ID: 6d9f32b5-a03b-46f8-b482-3cd91b4b6610 to be moved to 2pm tomorrow?"),
                        new com.mab.shared.model.ConversationTurn("assistant", "Calendar item updated: REMINDER \"Push code to origin main\" on 2026-03-14 at 14:00.")
                )
        );
        assertEquals("calendar", family);
    }

    @Test
    void fallbackKeepsBothRecipientsForDraftIdUpdate() {
        ToolsClient toolsClient = mock(ToolsClient.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        PlannerAgentService routedService = new PlannerAgentService(toolsClient, ollamaClient, new ObjectMapper());

        when(ollamaClient.generateJson(any())).thenReturn("{\"taskFamily\":\"email\"}");
        when(toolsClient.executePlan(argThat(plan ->
                "update_email_draft".equals(plan.action())
                        && "0758653c-00b8-429c-b847-9d5b40d78dad".equals(plan.targetEntityId())
                        && plan.arguments() != null
                        && plan.arguments().containsKey("recipientEmails")
                        && String.valueOf(plan.arguments().get("recipientEmails")).contains("alex@example.local")
                        && String.valueOf(plan.arguments().get("recipientEmails")).contains("joe@example.local")
        ))).thenAnswer(invocation -> {
            var appliedPlan = invocation.<com.mab.shared.model.PlannerActionPlan>getArgument(0);
            return new com.mab.shared.model.PlannerExecutionResult(
                "COMPLETED",
                "Email draft updated.",
                false,
                null,
                null,
                appliedPlan,
                new com.mab.shared.model.EmailDraftRecord(
                        "0758653c-00b8-429c-b847-9d5b40d78dad",
                        "alex@example.local, joe@example.local",
                        "Sam",
                        "Check-in Confirmation for Tomorrow",
                        "Body",
                        "professional",
                        "DRAFT",
                        null,
                        null,
                        "2026-03-12T19:00:00Z",
                        "2026-03-12T19:01:00Z"
                ),
                null,
                List.of("0758653c-00b8-429c-b847-9d5b40d78dad"),
                List.of("Check-in Confirmation for Tomorrow -> alex@example.local, joe@example.local")
            );
        });

        var response = routedService.execute(new AgentQueryRequest(
                "For the Draft ID: 0758653c-00b8-429c-b847-9d5b40d78dad, i need you to have 2 recipients - alex@example.local AND joe@example.local. the sender does not change.",
                List.of(),
                null
        ));

        assertEquals("COMPLETED", response.status());
        assertTrue(response.result().contains("alex@example.local, joe@example.local"));
    }

    @Test
    void rectifiesDraftIdRecipientQuestionAwayFromMetadata() {
        ToolsClient toolsClient = mock(ToolsClient.class);
        OllamaClient ollamaClient = mock(OllamaClient.class);
        PlannerAgentService routedService = new PlannerAgentService(toolsClient, ollamaClient, new ObjectMapper());

        when(ollamaClient.generateJson(any())).thenReturn("""
                {
                  "taskFamily":"metadata",
                  "action":"lookup_metadata",
                  "targetEntityId":"0758653c-00b8-429c-b847-9d5b40d78dad"
                }
                """);
        when(toolsClient.lookupRecords(any())).thenReturn(new RecordLookupResponse(
                "who are the current recipients for Draft ID: 0758653c-00b8-429c-b847-9d5b40d78dad?",
                "email_draft",
                null,
                "0758653c-00b8-429c-b847-9d5b40d78dad",
                null,
                "updated_at",
                "desc",
                true,
                1,
                new RecordLookupMatch(
                        "email_draft",
                        "0758653c-00b8-429c-b847-9d5b40d78dad",
                        null,
                        "Check-in Confirmation for Tomorrow",
                        "Body",
                        null,
                        null,
                        "DRAFT",
                        null,
                        "alex@example.com, joe@example.local",
                        "Sam",
                        null,
                        "2026-03-12T20:00:00Z",
                        "2026-03-12T20:10:00Z"
                ),
                List.of()
        ));

        var response = routedService.execute(new AgentQueryRequest(
                "who are the current recipients for Draft ID: 0758653c-00b8-429c-b847-9d5b40d78dad?",
                List.of(),
                null
        ));

        assertEquals("COMPLETED", response.status());
        assertEquals("The current recipients for draft ID 0758653c-00b8-429c-b847-9d5b40d78dad are alex@example.com, joe@example.local.", response.result());
        assertTrue(response.traces().stream().anyMatch(trace -> "RecordLookupTool".equals(trace.tool())));
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
