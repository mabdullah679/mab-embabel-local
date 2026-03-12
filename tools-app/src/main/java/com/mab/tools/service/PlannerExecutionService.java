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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class PlannerExecutionService {
    private static final Pattern EMAIL_ADDRESS_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern LIST_SPLIT_PATTERN = Pattern.compile("\\s*(?:,|;|\\band\\b|\\bwith\\b|&|\\n)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern UUID_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern GENERIC_SENDER_PATTERN = Pattern.compile("^(your name|\\[your name\\]|sender|the sender|from me|me|the contacts?|contacts?)$", Pattern.CASE_INSENSITIVE);

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
        Map<String, Object> arguments = normalizedArguments(plan);
        ContactResolution recipients = resolveRecipients(plan, arguments);
        if (recipients.ambiguous()) {
            return clarification("I found multiple matching recipients. Please specify which contact to use.", plan, List.of(), recipients.summaries());
        }
        if (recipients.emails().isEmpty()) {
            return clarification("Which recipient should I use for that email?", plan, List.of(), List.of());
        }

        String subject = text(arguments, "subject");
        String body = text(arguments, "body");
        String senderName = normalizedSenderName(text(arguments, "senderName"));
        if (isBlank(subject) || isBlank(body)) {
            return validationError("Email creation requires both subject and body.", plan);
        }
        if (isBlank(senderName)) {
            return clarification("Who should the sender be for that email?", plan, List.of());
        }

        EmailDraftRecord created = repository.createEmailDraft(
                String.join(", ", recipients.emails()),
                senderName,
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

        Map<String, Object> arguments = normalizedArguments(plan);
        ContactResolution recipients = resolveRecipients(plan, arguments);
        if (recipients.ambiguous()) {
            return clarification("I found multiple matching recipients. Please specify which contact to use.", plan, ids(matches), recipients.summaries());
        }
        String nextRecipient = recipients.emails().isEmpty() ? current.recipient() : String.join(", ", recipients.emails());
        String nextSenderName = coalesce(normalizedSenderName(text(arguments, "senderName")), current.senderName());
        String nextSubject = coalesce(text(arguments, "subject"), current.subject());
        String nextBody = coalesce(text(arguments, "body"), current.body());
        String nextTone = coalesce(normalizeTone(text(arguments, "tone")), current.tone());
        if (isBlank(nextSubject) || isBlank(nextBody)) {
            return validationError("Email updates must leave the draft with a subject and body.", plan);
        }
        if (isBlank(nextSenderName)) {
            return clarification("Who should the sender be for that email?", plan, ids(matches));
        }

        EmailDraftRecord updated = repository.updateEmailDraft(
                current.id(),
                nextRecipient,
                nextSenderName,
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

        String scheduledFor = text(normalizedArguments(plan), "scheduledFor");
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
        return new PlannerExecutionResult("COMPLETED", "Email draft deleted.", false, null, null, plan, current, null, List.of(current.id()), List.of("%s -> %s".formatted(current.subject(), current.recipient())));
    }

    private PlannerExecutionResult createCalendarItem(PlannerActionPlan plan) {
        Map<String, Object> arguments = normalizedArguments(plan);
        String title = text(arguments, "title");
        String date = text(arguments, "date");
        String time = text(arguments, "time");
        if (isBlank(title) || isBlank(date) || isBlank(time)) {
            return validationError("Calendar creation requires title, date, and time.", plan);
        }
        if (!isValidDate(date) || !isValidTime(time)) {
            return validationError("Calendar date/time values are invalid.", plan);
        }

        ContactResolution participants = resolveParticipants(plan, arguments);
        if (participants.ambiguous()) {
            return clarification("I found multiple matching participants. Please specify which contact to use.", plan, List.of(), participants.summaries());
        }
        String eventId = repository.createEvent(
                title.trim(),
                date.trim(),
                time.trim(),
                participants.emails(),
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
        Map<String, Object> arguments = normalizedArguments(plan);
        String nextDate = coalesce(text(arguments, "date"), current.date());
        String nextTime = coalesce(text(arguments, "time"), current.time());
        if (!isValidDate(nextDate) || !isValidTime(nextTime)) {
            return validationError("Calendar date/time values are invalid.", plan);
        }

        ContactResolution participants = resolveParticipants(plan, arguments);
        if (participants.ambiguous()) {
            return clarification("I found multiple matching participants. Please specify which contact to use.", plan, ids(matches), participants.summaries());
        }
        CalendarItemRecord updated = repository.updateCalendarItem(
                current.id(),
                coalesce(text(arguments, "title"), current.title()),
                nextDate,
                nextTime,
                participants.emails().isEmpty() ? current.participants() : participants.emails(),
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
        return new PlannerExecutionResult("COMPLETED", "Calendar item deleted.", false, null, null, plan, null, current, List.of(current.id()), List.of("%s on %s at %s".formatted(current.title(), current.date(), current.time())));
    }

    private List<EmailDraftRecord> matchEmailDrafts(PlannerActionPlan plan) {
        List<EmailDraftRecord> drafts = repository.listEmailDrafts();
        if (!isBlank(plan.targetEntityId()) && isUuid(plan.targetEntityId())) {
            List<EmailDraftRecord> direct = drafts.stream().filter(draft -> draft.id().equals(plan.targetEntityId())).toList();
            if (!direct.isEmpty()) {
                return direct;
            }
        }
        PlannerLookup lookup = plan.targetLookup();
        String reference = isBlank(plan.targetEntityId()) || isUuid(plan.targetEntityId()) ? null : plan.targetEntityId();
        if (lookup == null && isBlank(reference)) {
            return List.of();
        }
        return drafts.stream()
                .filter(draft -> contains(draft.subject(), lookup == null ? null : lookup.titleLike())
                        || contains(draft.subject(), lookup == null ? null : lookup.referenceText())
                        || contains(draft.body(), lookup == null ? null : lookup.referenceText())
                        || contains(draft.recipient(), lookup == null ? null : lookup.recipientEmail())
                        || recipientMatchesName(draft, lookup == null ? null : lookup.recipientName())
                        || contains(draft.recipient(), lookup == null ? null : lookup.referenceText())
                        || contains(draft.subject(), reference)
                        || contains(draft.body(), reference))
                .toList();
    }

    private List<CalendarItemRecord> matchCalendarItems(PlannerActionPlan plan) {
        List<CalendarItemRecord> items = repository.listCalendarItems();
        if (!isBlank(plan.targetEntityId()) && isUuid(plan.targetEntityId())) {
            List<CalendarItemRecord> direct = items.stream().filter(item -> item.id().equals(plan.targetEntityId())).toList();
            if (!direct.isEmpty()) {
                return direct;
            }
        }
        PlannerLookup lookup = plan.targetLookup();
        String reference = isBlank(plan.targetEntityId()) || isUuid(plan.targetEntityId()) ? null : plan.targetEntityId();
        if (lookup == null && isBlank(reference)) {
            return List.of();
        }
        List<ScoredCalendarMatch> matches = items.stream()
                .map(item -> scoreCalendarItem(item, lookup, reference))
                .filter(match -> match.score() > 0)
                .sorted(Comparator.comparingInt(ScoredCalendarMatch::score).reversed())
                .toList();
        if (matches.isEmpty()) {
            return List.of();
        }
        int topScore = matches.getFirst().score();
        return matches.stream()
                .filter(match -> match.score() == topScore)
                .map(ScoredCalendarMatch::item)
                .toList();
    }

    private ScoredCalendarMatch scoreCalendarItem(CalendarItemRecord item, PlannerLookup lookup, String reference) {
        int score = 0;
        if (!isBlank(reference) && contains(item.title(), reference)) {
            score += 3;
        }
        if (lookup != null) {
            if (contains(item.title(), lookup.titleLike())) {
                score += 4;
            }
            if (contains(item.title(), lookup.referenceText())) {
                score += 3;
            }
            if (matchesParticipant(item, lookup.participantName())) {
                score += 2;
            }
            if (equalsIgnoreCase(item.date(), lookup.date())) {
                score += 2;
            }
            if (timeMatches(item.time(), lookup.time())) {
                score += 2;
            }
            if (!isBlank(lookup.itemType()) && equalsIgnoreCase(item.itemType(), lookup.itemType())) {
                score += 1;
            }
        }
        return new ScoredCalendarMatch(item, score);
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
        for (ContactRecord contact : repository.findContactsByNames(expandNameTerms(List.of(participantName)))) {
            if (item.participants().stream().anyMatch(email -> email.equalsIgnoreCase(contact.email()))) {
                return true;
            }
        }
        return item.participants().stream().anyMatch(email -> contains(email, participantName));
    }

    private ContactResolution resolveRecipients(PlannerActionPlan plan, Map<String, Object> arguments) {
        LinkedHashSet<String> recipients = new LinkedHashSet<>(normalizeEmails(stringList(arguments.get("recipientEmails"))));
        List<String> nameTerms = new ArrayList<>();
        collectRecipientTerms(text(arguments, "recipient"), recipients, nameTerms);
        nameTerms.addAll(expandNameTerms(stringList(arguments.get("recipientName"))));
        if (!isBlank(text(arguments, "recipientEmail"))) {
            recipients.addAll(normalizeEmails(List.of(text(arguments, "recipientEmail"))));
        }
        PlannerLookup lookup = plan.targetLookup();
        if (lookup != null && !isBlank(lookup.recipientEmail())) {
            recipients.add(lookup.recipientEmail().trim().toLowerCase(Locale.ROOT));
        }
        nameTerms.addAll(expandNameTerms(stringList(arguments.get("recipientNames"))));
        if (lookup != null && !isBlank(lookup.recipientName())) {
            nameTerms.addAll(expandNameTerms(List.of(lookup.recipientName())));
        }
        ContactResolution resolved = resolveContactTerms(nameTerms);
        recipients.addAll(resolved.emails());
        return new ContactResolution(List.copyOf(recipients), resolved.summaries(), resolved.ambiguous());
    }

    private ContactResolution resolveParticipants(PlannerActionPlan plan, Map<String, Object> arguments) {
        List<String> rawParticipants = stringList(arguments.get("participants"));
        LinkedHashSet<String> participants = new LinkedHashSet<>(normalizeEmails(rawParticipants));
        List<String> nameTerms = new ArrayList<>();
        nameTerms.addAll(expandNameTerms(rawParticipants));
        PlannerLookup lookup = plan.targetLookup();
        if (lookup != null && !isBlank(lookup.participantName())) {
            nameTerms.addAll(expandNameTerms(List.of(lookup.participantName())));
        }
        ContactResolution resolved = resolveContactTerms(nameTerms);
        participants.addAll(resolved.emails());
        return new ContactResolution(List.copyOf(participants), resolved.summaries(), resolved.ambiguous());
    }

    private Map<String, Object> arguments(PlannerActionPlan plan) {
        return plan.arguments() == null ? Map.of() : plan.arguments();
    }

    private Map<String, Object> normalizedArguments(PlannerActionPlan plan) {
        Map<String, Object> original = arguments(plan);
        if (original.isEmpty() && plan.targetLookup() == null) {
            return original;
        }
        Map<String, Object> normalized = new java.util.LinkedHashMap<>(original);
        PlannerLookup lookup = plan.targetLookup();

        if (isBlank(text(normalized, "title"))) {
            putIfPresent(normalized, "title", firstNonBlank(
                    text(normalized, "summary"),
                    text(normalized, "name"),
                    lookup == null ? null : lookup.titleLike()
            ));
        }
        if (isBlank(text(normalized, "date"))) {
            putIfPresent(normalized, "date", firstNonBlank(
                    extractDate(text(normalized, "startDate")),
                    extractDate(text(normalized, "startTime")),
                    lookup == null ? null : lookup.date()
            ));
        }
        if (isBlank(text(normalized, "time"))) {
            putIfPresent(normalized, "time", firstNonBlank(
                    extractTime(text(normalized, "startDate")),
                    extractTime(text(normalized, "startTime")),
                    lookup == null ? null : lookup.time()
            ));
        }
        if (isBlank(text(normalized, "notes"))) {
            putIfPresent(normalized, "notes", coalesce(text(normalized, "description"), text(normalized, "location")));
        }
        if (!normalized.containsKey("participants")) {
            List<String> participants = participantInputs(normalized.get("attendees"));
            if (!participants.isEmpty()) {
                normalized.put("participants", participants);
            }
        }
        if (!normalized.containsKey("recipientNames") && !isBlank(text(normalized, "recipientName"))) {
            normalized.put("recipientNames", List.of(text(normalized, "recipientName")));
        }
        if (!normalized.containsKey("recipientEmails") && !isBlank(text(normalized, "recipientEmail"))) {
            normalized.put("recipientEmails", List.of(text(normalized, "recipientEmail")));
        }
        if (isBlank(text(normalized, "itemType"))) {
            putIfPresent(normalized, "itemType", normalizeItemTypeHint(firstNonBlank(
                    text(normalized, "taskType"),
                    text(normalized, "type"),
                    text(normalized, "targetEntityType"),
                    lookup == null ? null : lookup.itemType()
            )));
        }
        if (isBlank(text(normalized, "date"))) {
            putIfPresent(normalized, "date", extractDate(text(normalized, "dateTime")));
        }
        if (isBlank(text(normalized, "time"))) {
            putIfPresent(normalized, "time", extractTime(text(normalized, "dateTime")));
        }
        if (!normalized.containsKey("rewriteInstruction")) {
            if (truthy(normalized.get("makeItShorter")) || truthy(normalized.get("shorten"))) {
                normalized.put("rewriteInstruction", "make it shorter");
            } else if (!isBlank(text(normalized, "rewrite"))) {
                normalized.put("rewriteInstruction", text(normalized, "rewrite"));
            }
        }
        return normalized;
    }

    private String text(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private void putIfPresent(Map<String, Object> arguments, String key, String value) {
        if (!isBlank(value)) {
            arguments.put(key, value);
        }
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

    private List<String> participantInputs(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> participants = new ArrayList<>();
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> attendee) {
                    Object email = attendee.get("email");
                    Object name = attendee.get("name");
                    if (email != null && !String.valueOf(email).isBlank()) {
                        participants.add(String.valueOf(email));
                    } else if (name != null && !String.valueOf(name).isBlank()) {
                        participants.add(String.valueOf(name));
                    }
                } else if (entry != null) {
                    participants.add(String.valueOf(entry));
                }
            }
            return participants;
        }
        return stringList(value);
    }

    private List<String> splitCsv(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String part : LIST_SPLIT_PATTERN.split(value)) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    private void collectRecipientTerms(String value, LinkedHashSet<String> recipients, List<String> nameTerms) {
        if (isBlank(value)) {
            return;
        }
        for (String part : splitCsv(value)) {
            if (looksLikeEmail(part)) {
                recipients.add(part.trim().toLowerCase(Locale.ROOT));
            } else {
                nameTerms.addAll(expandNameTerms(List.of(part)));
            }
        }
    }

    private List<String> normalizeEmails(List<String> values) {
        return values.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .filter(this::looksLikeEmail)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .toList();
    }

    private List<String> expandNameTerms(List<String> values) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String value : values) {
            if (isBlank(value)) {
                continue;
            }
            for (String part : splitCsv(value)) {
                String cleaned = normalizeName(part);
                if (!cleaned.isBlank() && !looksLikeEmail(cleaned)) {
                    names.add(cleaned);
                }
            }
        }
        return List.copyOf(names);
    }

    private String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim();
        normalized = normalized.replaceAll("^[^\\p{Alnum}]+|[^\\p{Alnum}]+$", "");
        normalized = normalized.replaceAll("\\s+", " ");
        return normalized;
    }

    private boolean looksLikeEmail(String value) {
        return !isBlank(value) && EMAIL_ADDRESS_PATTERN.matcher(value.trim()).matches();
    }

    private String extractDate(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalDate().toString();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value).toLocalDate().toString();
            } catch (DateTimeParseException ignoredAgain) {
                return isValidDate(value) ? value.trim() : null;
            }
        }
    }

    private String extractTime(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toLocalTime().withSecond(0).withNano(0).toString();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value).toLocalTime().withSecond(0).withNano(0).toString();
            } catch (DateTimeParseException ignoredAgain) {
                return isValidTime(value) ? value.trim() : null;
            }
        }
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

    private String normalizedSenderName(String value) {
        if (isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        return GENERIC_SENDER_PATTERN.matcher(trimmed).matches() ? null : trimmed;
    }

    private String normalizeItemType(String value) {
        return isBlank(value) ? "MEETING" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeItemTypeHint(String value) {
        if (isBlank(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("reminder")) {
            return "REMINDER";
        }
        if (normalized.contains("task")) {
            return "TASK";
        }
        if (normalized.contains("meeting") || normalized.contains("calendar")) {
            return "MEETING";
        }
        return value.trim();
    }

    private String coalesce(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean isUuid(String value) {
        return !isBlank(value) && UUID_PATTERN.matcher(value.trim()).matches();
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

    private ContactResolution resolveContactTerms(List<String> nameTerms) {
        if (nameTerms == null || nameTerms.isEmpty()) {
            return new ContactResolution(List.of(), List.of(), false);
        }
        LinkedHashSet<String> emails = new LinkedHashSet<>();
        LinkedHashSet<String> summaries = new LinkedHashSet<>();
        boolean ambiguous = false;
        for (String term : nameTerms) {
            List<ContactRecord> matches = repository.findContactsByNames(List.of(term));
            List<ContactRecord> exactMatches = matches.stream()
                    .filter(contact -> normalizeNameKey(contact.name()).equals(normalizeNameKey(term))
                            || firstName(contact.name()).equals(normalizeNameKey(term)))
                    .toList();
            List<ContactRecord> usable = exactMatches.isEmpty() ? matches : exactMatches;
            if (usable.size() == 1) {
                emails.add(usable.getFirst().email().trim().toLowerCase(Locale.ROOT));
                continue;
            }
            if (usable.size() > 1) {
                ambiguous = true;
                usable.stream()
                        .map(contact -> "%s <%s>".formatted(contact.name(), contact.email()))
                        .forEach(summaries::add);
            }
        }
        return new ContactResolution(List.copyOf(emails), List.copyOf(summaries), ambiguous);
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value).trim());
    }

    private String normalizeNameKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String firstName(String value) {
        String normalized = normalizeNameKey(value);
        int index = normalized.indexOf(' ');
        return index >= 0 ? normalized.substring(0, index) : normalized;
    }

    private PlannerExecutionResult clarification(String question, PlannerActionPlan plan, List<String> candidateIds) {
        return clarification(question, plan, candidateIds, List.of());
    }

    private PlannerExecutionResult clarification(String question, PlannerActionPlan plan, List<String> candidateIds, List<String> candidateSummaries) {
        return new PlannerExecutionResult("CLARIFICATION", question, true, coalesce(question, "Please clarify the target."), null, plan, null, null, candidateIds, candidateSummaries);
    }

    private PlannerExecutionResult validationError(String message, PlannerActionPlan plan) {
        return new PlannerExecutionResult("VALIDATION_ERROR", message, false, null, message, plan, null, null, List.of(), List.of());
    }

    private PlannerExecutionResult completed(String message, PlannerActionPlan plan, EmailDraftRecord draft, CalendarItemRecord item) {
        List<String> candidateIds = draft != null ? List.of(draft.id()) : item != null ? List.of(item.id()) : List.of();
        List<String> candidateSummaries = draft != null
                ? List.of("%s -> %s".formatted(draft.subject(), draft.recipient()))
                : item != null ? List.of("%s on %s at %s".formatted(item.title(), item.date(), item.time())) : List.of();
        return new PlannerExecutionResult("COMPLETED", message, false, null, null, plan, draft, item, candidateIds, candidateSummaries);
    }

    private record ContactResolution(List<String> emails, List<String> summaries, boolean ambiguous) {
    }

    private record ScoredCalendarMatch(CalendarItemRecord item, int score) {
    }
}
