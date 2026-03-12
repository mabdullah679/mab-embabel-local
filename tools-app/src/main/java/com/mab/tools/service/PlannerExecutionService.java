package com.mab.tools.service;

import com.mab.shared.model.CalendarItemRecord;
import com.mab.shared.model.ContactRecord;
import com.mab.shared.model.EmailDraftRecord;
import com.mab.shared.model.PlannerActionPlan;
import com.mab.shared.model.PlannerExecutionResult;
import com.mab.shared.model.PlannerLookup;
import com.mab.tools.repository.ToolRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PlannerExecutionService {

    private final ToolRepository repository;

    public PlannerExecutionService(ToolRepository repository) {
        this.repository = repository;
    }

    public PlannerExecutionResult execute(PlannerActionPlan plan) {
        if (plan == null) {
            return validationError("Planner did not provide an action plan.", null);
        }
        if (plan.needsClarification()) {
            return clarification(plan.clarificationQuestion(), plan, List.of());
        }
        if (isBlank(plan.action())) {
            return validationError("Planner action is missing.", plan);
        }

        return switch (plan.action()) {
            case "create_email_draft" -> createEmailDraft(plan);
            case "update_email_draft" -> updateEmailDraft(plan);
            case "schedule_email_draft" -> scheduleEmailDraft(plan);
            case "send_email_draft" -> sendEmailDraft(plan);
            case "delete_email_draft" -> deleteEmailDraft(plan);
            case "create_calendar_item" -> createCalendarItem(plan);
            case "update_calendar_item" -> updateCalendarItem(plan);
            case "delete_calendar_item" -> deleteCalendarItem(plan);
            default -> validationError("Unsupported planner action: " + plan.action(), plan);
        };
    }

    private PlannerExecutionResult createEmailDraft(PlannerActionPlan plan) {
        Map<String, Object> arguments = arguments(plan);
        List<String> recipients = resolveRecipients(plan, arguments);
        if (recipients.isEmpty()) {
            return clarification("Which recipient should I use for that email?", plan, List.of());
        }

        String subject = text(arguments, "subject");
        String body = text(arguments, "body");
        if (isBlank(subject) || isBlank(body)) {
            return validationError("Email creation requires both subject and body.", plan);
        }

        EmailDraftRecord created = repository.createEmailDraft(
                String.join(", ", recipients),
                subject.trim(),
                normalizeBody(body),
                normalizeTone(text(arguments, "tone"))
        );
        return completed("Email draft created.", plan, created, null);
    }

    private PlannerExecutionResult updateEmailDraft(PlannerActionPlan plan) {
        List<EmailDraftRecord> matches = matchEmailDrafts(plan);
        if (matches.isEmpty()) {
            return clarification("I couldn't identify which email draft to update.", plan, List.of());
        }
        if (matches.size() > 1) {
            return clarification("I found multiple matching email drafts. Please specify which draft to update.", plan, ids(matches));
        }

        EmailDraftRecord current = matches.getFirst();
        if ("SENT".equalsIgnoreCase(current.status())) {
            return validationError("Sent email drafts cannot be updated.", plan);
        }

        Map<String, Object> arguments = arguments(plan);
        List<String> recipients = resolveRecipients(plan, arguments);
        String nextRecipient = recipients.isEmpty() ? current.recipient() : String.join(", ", recipients);
        String nextSubject = coalesce(text(arguments, "subject"), current.subject());
        String nextBody = coalesce(text(arguments, "body"), current.body());
        String nextTone = coalesce(normalizeTone(text(arguments, "tone")), current.tone());
        if (isBlank(nextSubject) || isBlank(nextBody)) {
            return validationError("Email updates must leave the draft with a subject and body.", plan);
        }

        EmailDraftRecord updated = repository.updateEmailDraft(
                current.id(),
                nextRecipient,
                nextSubject,
                normalizeBody(nextBody),
                nextTone
        );
        return completed("Email draft updated.", plan, updated, null);
    }

    private PlannerExecutionResult scheduleEmailDraft(PlannerActionPlan plan) {
        List<EmailDraftRecord> matches = matchEmailDrafts(plan);
        if (matches.isEmpty()) {
            return clarification("I couldn't identify which email draft to schedule.", plan, List.of());
        }
        if (matches.size() > 1) {
            return clarification("I found multiple matching email drafts. Please specify which draft to schedule.", plan, ids(matches));
        }

        EmailDraftRecord current = matches.getFirst();
        if ("SENT".equalsIgnoreCase(current.status())) {
            return validationError("Sent email drafts cannot be scheduled.", plan);
        }

        String scheduledFor = text(arguments(plan), "scheduledFor");
        if (isBlank(scheduledFor)) {
            return clarification("What time should I schedule that email for?", plan, ids(matches));
        }
        if (!isFutureTimestamp(scheduledFor)) {
            return validationError("Scheduled email timestamps must be future ISO-8601 values.", plan);
        }

        EmailDraftRecord updated = repository.scheduleEmailDraft(current.id(), scheduledFor);
        return completed("Email draft scheduled.", plan, updated, null);
    }

    private PlannerExecutionResult sendEmailDraft(PlannerActionPlan plan) {
        List<EmailDraftRecord> matches = matchEmailDrafts(plan);
        if (matches.isEmpty()) {
            return clarification("I couldn't identify which email draft to send.", plan, List.of());
        }
        if (matches.size() > 1) {
            return clarification("I found multiple matching email drafts. Please specify which draft to send.", plan, ids(matches));
        }

        EmailDraftRecord current = matches.getFirst();
        if ("SENT".equalsIgnoreCase(current.status())) {
            return validationError("That email draft has already been sent.", plan);
        }

        EmailDraftRecord updated = repository.markEmailDraftSent(current.id());
        return completed("Email draft sent.", plan, updated, null);
    }

    private PlannerExecutionResult deleteEmailDraft(PlannerActionPlan plan) {
        List<EmailDraftRecord> matches = matchEmailDrafts(plan);
        if (matches.isEmpty()) {
            return clarification("I couldn't identify which email draft to delete.", plan, List.of());
        }
        if (matches.size() > 1) {
            return clarification("I found multiple matching email drafts. Please specify which draft to delete.", plan, ids(matches));
        }

        EmailDraftRecord current = matches.getFirst();
        repository.deleteEmailDraft(current.id());
        return new PlannerExecutionResult("COMPLETED", "Email draft deleted.", false, null, null, plan, current, null, List.of(current.id()));
    }

    private PlannerExecutionResult createCalendarItem(PlannerActionPlan plan) {
        Map<String, Object> arguments = arguments(plan);
        String title = text(arguments, "title");
        String date = text(arguments, "date");
        String time = text(arguments, "time");
        if (isBlank(title) || isBlank(date) || isBlank(time)) {
            return validationError("Calendar creation requires title, date, and time.", plan);
        }
        if (!isValidDate(date) || !isValidTime(time)) {
            return validationError("Calendar date/time values are invalid.", plan);
        }

        List<String> participants = resolveParticipants(plan, arguments);
        String eventId = repository.createEvent(
                title.trim(),
                date.trim(),
                time.trim(),
                participants,
                normalizeItemType(coalesce(text(arguments, "itemType"), plan.targetLookup() == null ? null : plan.targetLookup().itemType())),
                coalesce(text(arguments, "notes"), "")
        );
        return completed("Calendar item created.", plan, null, repository.findCalendarItem(eventId));
    }

    private PlannerExecutionResult updateCalendarItem(PlannerActionPlan plan) {
        List<CalendarItemRecord> matches = matchCalendarItems(plan);
        if (matches.isEmpty()) {
            return clarification("I couldn't identify which calendar item to update.", plan, List.of());
        }
        if (matches.size() > 1) {
            return clarification("I found multiple matching calendar items. Please specify which one to update.", plan, ids(matches));
        }

        CalendarItemRecord current = matches.getFirst();
        Map<String, Object> arguments = arguments(plan);
        String nextDate = coalesce(text(arguments, "date"), current.date());
        String nextTime = coalesce(text(arguments, "time"), current.time());
        if (!isValidDate(nextDate) || !isValidTime(nextTime)) {
            return validationError("Calendar date/time values are invalid.", plan);
        }

        List<String> participants = resolveParticipants(plan, arguments);
        CalendarItemRecord updated = repository.updateCalendarItem(
                current.id(),
                coalesce(text(arguments, "title"), current.title()),
                nextDate,
                nextTime,
                participants.isEmpty() ? current.participants() : participants,
                normalizeItemType(coalesce(text(arguments, "itemType"), current.itemType())),
                coalesce(text(arguments, "notes"), current.notes())
        );
        return completed("Calendar item updated.", plan, null, updated);
    }

    private PlannerExecutionResult deleteCalendarItem(PlannerActionPlan plan) {
        List<CalendarItemRecord> matches = matchCalendarItems(plan);
        if (matches.isEmpty()) {
            return clarification("I couldn't identify which calendar item to delete.", plan, List.of());
        }
        if (matches.size() > 1) {
            return clarification("I found multiple matching calendar items. Please specify which one to delete.", plan, ids(matches));
        }

        CalendarItemRecord current = matches.getFirst();
        repository.deleteCalendarItem(current.id());
        return new PlannerExecutionResult("COMPLETED", "Calendar item deleted.", false, null, null, plan, null, current, List.of(current.id()));
    }

    private List<EmailDraftRecord> matchEmailDrafts(PlannerActionPlan plan) {
        List<EmailDraftRecord> drafts = repository.listEmailDrafts();
        if (!isBlank(plan.targetEntityId())) {
            return drafts.stream().filter(draft -> draft.id().equals(plan.targetEntityId())).toList();
        }
        PlannerLookup lookup = plan.targetLookup();
        if (lookup == null) {
            return List.of();
        }
        return drafts.stream()
                .filter(draft -> contains(draft.subject(), lookup.titleLike())
                        || contains(draft.subject(), lookup.referenceText())
                        || contains(draft.body(), lookup.referenceText())
                        || contains(draft.recipient(), lookup.recipientEmail())
                        || recipientMatchesName(draft, lookup.recipientName())
                        || contains(draft.recipient(), lookup.referenceText()))
                .toList();
    }

    private List<CalendarItemRecord> matchCalendarItems(PlannerActionPlan plan) {
        List<CalendarItemRecord> items = repository.listCalendarItems();
        if (!isBlank(plan.targetEntityId())) {
            return items.stream().filter(item -> item.id().equals(plan.targetEntityId())).toList();
        }
        PlannerLookup lookup = plan.targetLookup();
        if (lookup == null) {
            return List.of();
        }
        return items.stream()
                .filter(item -> contains(item.title(), lookup.titleLike())
                        || contains(item.title(), lookup.referenceText())
                        || matchesParticipant(item, lookup.participantName())
                        || equalsIgnoreCase(item.date(), lookup.date())
                        || timeMatches(item.time(), lookup.time()))
                .filter(item -> isBlank(lookup.itemType()) || equalsIgnoreCase(item.itemType(), lookup.itemType()))
                .toList();
    }

    private boolean recipientMatchesName(EmailDraftRecord draft, String name) {
        if (isBlank(name)) {
            return false;
        }
        for (ContactRecord contact : repository.findContactsByNames(List.of(name))) {
            if (contains(draft.recipient(), contact.email())) {
                return true;
            }
        }
        return contains(draft.recipient(), name.replace(' ', '.'));
    }

    private boolean matchesParticipant(CalendarItemRecord item, String participantName) {
        if (isBlank(participantName)) {
            return false;
        }
        for (ContactRecord contact : repository.findContactsByNames(List.of(participantName))) {
            if (item.participants().stream().anyMatch(email -> email.equalsIgnoreCase(contact.email()))) {
                return true;
            }
        }
        return item.participants().stream().anyMatch(email -> contains(email, participantName));
    }

    private List<String> resolveRecipients(PlannerActionPlan plan, Map<String, Object> arguments) {
        LinkedHashSet<String> recipients = new LinkedHashSet<>(stringList(arguments.get("recipientEmails")));
        if (!isBlank(text(arguments, "recipient"))) {
            recipients.addAll(splitCsv(text(arguments, "recipient")));
        }
        PlannerLookup lookup = plan.targetLookup();
        if (lookup != null && !isBlank(lookup.recipientEmail())) {
            recipients.add(lookup.recipientEmail().trim().toLowerCase(Locale.ROOT));
        }

        List<String> names = new ArrayList<>(stringList(arguments.get("recipientNames")));
        if (lookup != null && !isBlank(lookup.recipientName())) {
            names.add(lookup.recipientName());
        }
        if (!names.isEmpty()) {
            repository.findContactsByNames(names).stream()
                    .map(ContactRecord::email)
                    .forEach(recipients::add);
        }
        return List.copyOf(recipients);
    }

    private List<String> resolveParticipants(PlannerActionPlan plan, Map<String, Object> arguments) {
        LinkedHashSet<String> participants = new LinkedHashSet<>(stringList(arguments.get("participants")));
        PlannerLookup lookup = plan.targetLookup();
        if (lookup != null && !isBlank(lookup.participantName())) {
            repository.findContactsByNames(List.of(lookup.participantName())).stream()
                    .map(ContactRecord::email)
                    .forEach(participants::add);
        }
        return List.copyOf(participants);
    }

    private Map<String, Object> arguments(PlannerActionPlan plan) {
        return plan.arguments() == null ? Map.of() : plan.arguments();
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private List<String> stringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).map(String::trim).filter(item -> !item.isBlank()).toList();
        }
        return splitCsv(String.valueOf(value));
    }

    private List<String> splitCsv(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                items.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        return items;
    }

    private boolean contains(String value, String search) {
        return !isBlank(value) && !isBlank(search) && value.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
    }

    private boolean equalsIgnoreCase(String left, String right) {
        return !isBlank(left) && !isBlank(right) && left.equalsIgnoreCase(right);
    }

    private boolean timeMatches(String stored, String candidate) {
        return !isBlank(stored) && !isBlank(candidate) && stored.startsWith(candidate.trim());
    }

    private boolean isValidDate(String value) {
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private boolean isValidTime(String value) {
        try {
            LocalTime.parse(value);
            return true;
        } catch (DateTimeParseException ignored) {
            return false;
        }
    }

    private boolean isFutureTimestamp(String value) {
        try {
            return OffsetDateTime.parse(value).isAfter(OffsetDateTime.now());
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value).isAfter(LocalDateTime.now());
            } catch (DateTimeParseException ignoredAgain) {
                return false;
            }
        }
    }

    private String normalizeBody(String value) {
        String trimmed = isBlank(value) ? "" : value.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        return Character.toUpperCase(trimmed.charAt(0)) + trimmed.substring(1);
    }

    private String normalizeTone(String value) {
        return isBlank(value) ? "professional" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeItemType(String value) {
        return isBlank(value) ? "MEETING" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String coalesce(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private List<String> ids(List<?> records) {
        return records.stream().map(record -> {
            if (record instanceof EmailDraftRecord draft) {
                return draft.id();
            }
            if (record instanceof CalendarItemRecord item) {
                return item.id();
            }
            return String.valueOf(record);
        }).toList();
    }

    private PlannerExecutionResult clarification(String question, PlannerActionPlan plan, List<String> candidateIds) {
        return new PlannerExecutionResult("CLARIFICATION", question, true, coalesce(question, "Please clarify the target."), null, plan, null, null, candidateIds);
    }

    private PlannerExecutionResult validationError(String message, PlannerActionPlan plan) {
        return new PlannerExecutionResult("VALIDATION_ERROR", message, false, null, message, plan, null, null, List.of());
    }

    private PlannerExecutionResult completed(String message, PlannerActionPlan plan, EmailDraftRecord draft, CalendarItemRecord item) {
        List<String> candidateIds = draft != null ? List.of(draft.id()) : item != null ? List.of(item.id()) : List.of();
        return new PlannerExecutionResult("COMPLETED", message, false, null, null, plan, draft, item, candidateIds);
    }
}
