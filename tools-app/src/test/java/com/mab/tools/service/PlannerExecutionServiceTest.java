package com.mab.tools.service;

import com.mab.shared.model.CalendarItemRecord;
import com.mab.shared.model.ContactRecord;
import com.mab.shared.model.EmailDraftRecord;
import com.mab.shared.model.PlannerActionPlan;
import com.mab.tools.repository.ToolRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlannerExecutionServiceTest {

    private final ToolRepository repository = mock(ToolRepository.class);
    private final PlannerExecutionService service = new PlannerExecutionService(repository);

    @Test
    void resolvesNaturalRecipientNamesAgainstContacts() {
        when(repository.findContactsByNames(anyList())).thenReturn(List.of(
                new ContactRecord("1", "Alex Nguyen", "alex@example.com", "2026-03-12T10:00:00Z"),
                new ContactRecord("2", "Joe Miller", "joe@example.com", "2026-03-12T10:00:00Z")
        ));
        when(repository.createEmailDraft(
                anyString(),
                eq("John Doe"),
                eq("Meeting notice"),
                anyString(),
                eq("professional")
        )).thenReturn(new EmailDraftRecord(
                "draft-1",
                "alex@example.com, joe@example.com",
                "John Doe",
                "Meeting notice",
                "Please join the meeting.",
                "professional",
                "DRAFT",
                null,
                null,
                "2026-03-12T10:00:00Z",
                "2026-03-12T10:00:00Z"
        ));

        PlannerActionPlan plan = new PlannerActionPlan(
                "email",
                "create_email_draft",
                "email_draft",
                null,
                null,
                Map.of(
                        "recipient", "Joe and Alex",
                        "senderName", "John Doe",
                        "subject", "Meeting notice",
                        "body", "please join the meeting.",
                        "tone", "professional"
                ),
                false,
                null,
                0.98
        );

        var result = service.execute(plan);

        assertEquals("COMPLETED", result.status());
        assertEquals("alex@example.com, joe@example.com", result.emailDraft().recipient());
        verify(repository).findContactsByNames(eq(List.of("Joe")));
        verify(repository).findContactsByNames(eq(List.of("Alex")));
    }

    @Test
    void resolvesRecipientNameWhenPlannerPutsItInArguments() {
        when(repository.findContactsByNames(anyList())).thenReturn(List.of(
                new ContactRecord("1", "Joe Miller", "joe@example.com", "2026-03-12T10:00:00Z")
        ));
        when(repository.createEmailDraft(
                eq("joe@example.com"),
                eq("John Doe"),
                eq("Hello"),
                eq("Hi Joe."),
                eq("friendly")
        )).thenReturn(new EmailDraftRecord(
                "draft-2",
                "joe@example.com",
                "John Doe",
                "Hello",
                "Hi Joe.",
                "friendly",
                "DRAFT",
                null,
                null,
                "2026-03-12T10:00:00Z",
                "2026-03-12T10:00:00Z"
        ));

        PlannerActionPlan plan = new PlannerActionPlan(
                "email",
                "create_email_draft",
                "email_draft",
                null,
                null,
                Map.of(
                        "recipientName", "Joe Miller",
                        "senderName", "John Doe",
                        "subject", "Hello",
                        "body", "Hi Joe.",
                        "tone", "friendly"
                ),
                false,
                null,
                1.0
        );

        var result = service.execute(plan);

        assertEquals("COMPLETED", result.status());
        assertEquals("joe@example.com", result.emailDraft().recipient());
    }

    @Test
    void resolvesNaturalCalendarParticipantsAgainstContacts() {
        when(repository.findContactsByNames(anyList())).thenReturn(List.of(
                new ContactRecord("1", "Alex Nguyen", "alex@example.com", "2026-03-12T10:00:00Z"),
                new ContactRecord("2", "Joe Miller", "joe@example.com", "2026-03-12T10:00:00Z")
        ));
        when(repository.createEvent(
                eq("Lunch"),
                eq("2026-03-13"),
                eq("15:00"),
                eq(List.of("alex@example.com", "joe@example.com")),
                eq("MEETING"),
                eq("")
        )).thenReturn("event-1");
        when(repository.findCalendarItem("event-1")).thenReturn(new CalendarItemRecord(
                "event-1",
                "Lunch",
                "2026-03-13",
                "15:00",
                List.of("alex@example.com", "joe@example.com"),
                "CREATED",
                "MEETING",
                "",
                "2026-03-12T10:00:00Z"
        ));

        PlannerActionPlan plan = new PlannerActionPlan(
                "calendar",
                "create_calendar_item",
                "calendar_item",
                null,
                null,
                Map.of(
                        "title", "Lunch",
                        "date", "2026-03-13",
                        "time", "15:00",
                        "participants", "Alex and Joe"
                ),
                false,
                null,
                0.97
        );

        var result = service.execute(plan);

        assertEquals("COMPLETED", result.status());
        assertEquals(List.of("alex@example.com", "joe@example.com"), result.calendarItem().participants());
        verify(repository).findContactsByNames(eq(List.of("Alex")));
        verify(repository).findContactsByNames(eq(List.of("Joe")));
    }

    @Test
    void normalizesPlannerCalendarPayloadShape() {
        when(repository.findContactsByNames(anyList())).thenReturn(List.of(
                new ContactRecord("1", "Alex Nguyen", "alex@example.com", "2026-03-12T10:00:00Z")
        ));
        when(repository.createEvent(
                eq("Lunch with Alex"),
                eq("2026-03-13"),
                eq("13:00"),
                eq(List.of("alex@example.com")),
                eq("MEETING"),
                eq("")
        )).thenReturn("event-2");
        when(repository.findCalendarItem("event-2")).thenReturn(new CalendarItemRecord(
                "event-2",
                "Lunch with Alex",
                "2026-03-13",
                "13:00",
                List.of("alex@example.com"),
                "CREATED",
                "MEETING",
                "",
                "2026-03-12T10:00:00Z"
        ));

        PlannerActionPlan plan = new PlannerActionPlan(
                "calendar",
                "create_calendar_item",
                "calendar_event",
                null,
                null,
                Map.of(
                        "summary", "Lunch with Alex",
                        "startDate", "2026-03-13T13:00:00",
                        "attendees", List.of(Map.of("name", "Alex", "email", ""))
                ),
                false,
                null,
                1.0
        );

        var result = service.execute(plan);

        assertEquals("COMPLETED", result.status());
        assertEquals("Lunch with Alex", result.calendarItem().title());
        assertEquals("2026-03-13", result.calendarItem().date());
        assertEquals("13:00", result.calendarItem().time());
    }

    @Test
    void normalizesReminderPayloadUsingStartTimeAndLookupTitle() {
        when(repository.createEvent(
                eq("Push code to origin main"),
                eq("2026-03-12"),
                eq("12:00"),
                eq(List.of()),
                eq("MEETING"),
                eq("")
        )).thenReturn("event-3");
        when(repository.findCalendarItem("event-3")).thenReturn(new CalendarItemRecord(
                "event-3",
                "Push code to origin main",
                "2026-03-12",
                "12:00",
                List.of(),
                "CREATED",
                "MEETING",
                "",
                "2026-03-12T10:00:00Z"
        ));

        PlannerActionPlan plan = new PlannerActionPlan(
                "calendar",
                "create_calendar_item",
                "calendar_item",
                null,
                new com.mab.shared.model.PlannerLookup(null, null, null, "Push code to origin main", null, null, null, null, null),
                Map.of(
                        "startTime", "2026-03-12T12:00:00",
                        "endTime", "2026-03-12T12:00:00"
                ),
                false,
                        null,
                1.0
        );

        var result = service.execute(plan);

        assertEquals("COMPLETED", result.status());
        assertEquals("Push code to origin main", result.calendarItem().title());
        assertEquals("2026-03-12", result.calendarItem().date());
        assertEquals("12:00", result.calendarItem().time());
    }
}
