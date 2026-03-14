package com.mab.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mab.orchestrator.client.OllamaClient;
import com.mab.orchestrator.client.ToolsClient;
import com.mab.shared.model.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PlannerAgentService {
    private static final int RECENT_TURN_LIMIT = 4;
    private static final Pattern EVENT_TIME_PATTERN = Pattern.compile("\\b(today|tomorrow|tonight|next\\s+\\w+|at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?|\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT_PARTICIPANT_PATTERN = Pattern.compile("\\bwith\\s+[a-z][a-z\\s.'&-]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_SENDER_PATTERN = Pattern.compile("\\b(?:my name is|i am|i'm|sender is|sender:?|from me as)\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,3})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAREN_SENDER_PATTERN = Pattern.compile("\\bfrom me\\s*\\(\\s*([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,3})\\s*\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern UUID_PATTERN = Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b(email|e-mail|draft|reply|send|subject|tone)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALENDAR_PATTERN = Pattern.compile("\\b(calendar|meeting|event|reminder|task|schedule|reschedule|move|cancel|lunch|dinner|coffee|breakfast|check-?in|sync|standup|call|appointment|interview|demo|catch-?up)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern METADATA_PATTERN = Pattern.compile("\\b(metadata|uuid)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HARDWARE_PATTERN = Pattern.compile("\\b(hardware|device|workstation|laptop|desktop|specs?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAG_PATTERN = Pattern.compile("\\b(docs?|documents?|knowledge|architecture|rag|reference|manual)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECORD_QUERY_PATTERN = Pattern.compile("\\b(what was|what is|what are|show|find|look up|lookup|which|last|latest|most recent|details|detail|tell me more|more detail|do i have|have i|list|who are|who is|current recipients|current recipient|recipients?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECORD_ENTITY_PATTERN = Pattern.compile("\\b(reminder|task|meeting|event|calendar item|draft|email draft)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_ACTION_PATTERN = Pattern.compile("\\b(email|e-mail|draft|reply|send|rewrite|shorten|subject|recipient|body)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALENDAR_ACTION_PATTERN = Pattern.compile("\\b(calendar|meeting|event|reminder|task|schedule|reschedule|move|cancel|book|appointment|lunch|dinner|coffee|breakfast|check-?in|sync|standup|call|interview|demo|catch-?up)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRONG_EMAIL_INTENT_PATTERN = Pattern.compile("\\b(email|e-mail|draft|reply|send|rewrite|shorten)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRONG_CALENDAR_INTENT_PATTERN = Pattern.compile("\\b(calendar|schedule|reschedule|move|cancel|book|appointment)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAG_ACTION_PATTERN = Pattern.compile("\\b(what do|what does|summari[sz]e|explain|tell me|according to|where in)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MUTATION_PATTERN = Pattern.compile("\\b(create|update|delete|send|schedule|reschedule|move|cancel|book|reply|rewrite|shorten|write|build|add|change|remove|edit)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLLOW_UP_PATTERN = Pattern.compile("\\b(it|that|them|those|this|previous|above|same|again|shorter|longer|rewrite|send it|schedule it|update it|delete it|use that|instead|also|now)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELF_CONTAINED_PATTERN = Pattern.compile("\\b(email|draft|meeting|calendar|metadata|uuid|hardware|docs?|document|architecture|rag)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHAT_PATTERN = Pattern.compile("\\b(hi|hello|hey|thanks|thank you|who are you|what can you do|how are you|why|can you explain|explain that|tell me more|help me understand|good morning|good afternoon|good evening|sorry)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TASK_REQUEST_PATTERN = Pattern.compile("\\b(create|update|delete|send|schedule|draft|lookup|search|find|book|cancel|move|ingest|write|build)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_SENDER_PATTERN = Pattern.compile("^(the sender|sender|me|myself|from me|your name|the contacts?|contacts?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALENDAR_FOLLOW_UP_PATTERN = Pattern.compile("\\b(the one|titled|default title|change the task type|change the type|make it a reminder|make it a task|make it a meeting|reminder|meeting|task)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_FOLLOW_UP_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$|\\b(recipient|sender|send it|schedule it|make it shorter|rewrite it|joe@example|alex@example)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_DRAFT_REFERENCE_PATTERN = Pattern.compile("\\b(draft id|email draft|draft\\s+[0-9a-fA-F]{8}-)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT_ID_REFERENCE_PATTERN = Pattern.compile("\\b(event id|calendar item id|reminder id|task id)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_ONLY_FOLLOW_UP_PATTERN = Pattern.compile("^(?:at\\s+)?\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?(?:\\s+(?:today|tomorrow))?\\.?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_ONLY_PATTERN = Pattern.compile("^[A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,3}\\.?$");
    private static final Pattern QUOTED_TEXT_PATTERN = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern TITLE_REFERENCE_PATTERN = Pattern.compile("\\btitled\\s+\"([^\"]+)\"|\\btitled\\s+([A-Za-z0-9][A-Za-z0-9\\s'._-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TODAY_PATTERN = Pattern.compile("\\btoday\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOMORROW_PATTERN = Pattern.compile("\\btomorrow\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKDAY_PATTERN = Pattern.compile("\\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_PATTERN = Pattern.compile("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_DATE_PATTERN = Pattern.compile("\\b(january|february|march|april|may|june|july|august|september|october|november|december)\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,\\s*(\\d{4}))?\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_ADDRESS_CAPTURE_PATTERN = Pattern.compile("\\b[^\\s,;:]+@[^\\s,;:]+\\.[^\\s,;:]+\\b");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\[[^\\]]+]|<[^>]+>");
    private static final Pattern PLATFORM_PATTERN = Pattern.compile("\\b(zoom|teams|google meet|conference room|room [a-z0-9-]+)\\b", Pattern.CASE_INSENSITIVE);

    private final ToolsClient toolsClient;
    private final OllamaClient ollamaClient;
    private final ObjectMapper objectMapper;

    public PlannerAgentService(ToolsClient toolsClient, OllamaClient ollamaClient, ObjectMapper objectMapper) {
        this.toolsClient = toolsClient;
        this.ollamaClient = ollamaClient;
        this.objectMapper = objectMapper;
    }

    public AgentQueryResponse execute(AgentQueryRequest request) {
        String query = request.query().trim();
        List<ConversationTurn> rawHistory = request.history() == null ? List.of() : request.history();
        PendingClarification pendingClarification = request.pendingClarification();
        List<ToolTrace> traces = new ArrayList<>();
        if (query.isBlank()) {
            return respond("Please provide a request.", traces, 0, "VALIDATION_ERROR", null, null, pendingClarification);
        }
        ContextSelection context = selectContext(query, rawHistory, traces);
        List<ConversationTurn> history = context.history();
        NormalizedRequest normalized = normalizeRequest(query, history, pendingClarification);

        String family = normalized.family();
        addTrace(traces, "PlannerFamilySelection", query, "{\"taskFamily\":\"" + family + "\"}", true, 0);
        if ("clarification".equals(family)) {
            return respond("I can help with that, but the request mixes multiple task types. Tell me whether you want an email action or a calendar action first.", traces, traces.size(), "CLARIFICATION", null, null, pendingClarification);
        }
        if ("chat".equals(family)) {
            String answer = respondChat(query, history, traces);
            return respond(answer, traces, traces.size(), "COMPLETED", null, null, null);
        }
        if ("unsupported".equals(family)) {
            return respond(outOfScopeResponse(), traces, traces.size(), "VALIDATION_ERROR", null, null, null);
        }

        PlannerActionPlan plan = continuePendingClarification(query, history, pendingClarification, traces);
        if (plan == null) {
            plan = plan(query, history, family, traces);
        }
        if (plan == null) {
            PlannerActionPlan fallbackPlan = heuristicFallbackPlan(normalized, history);
            if (fallbackPlan == null) {
                return respond("I couldn't build a valid action plan for that request.", traces, traces.size(), "VALIDATION_ERROR", null, null, pendingClarification);
            }
            addTrace(traces, "PlannerFallback", query, toJson(fallbackPlan), true, 0);
            plan = fallbackPlan;
        }
        plan = rectifyPlanForDeterministicReferences(query, history, plan, traces);
        if (plan.needsClarification()) {
            PendingClarification pending = new PendingClarification(plan.taskFamily(), plan.action(), plan.clarificationQuestion(), plan, List.of(), List.of());
            return respond(plan.clarificationQuestion(), traces, traces.size(), "CLARIFICATION", plan, null, pending);
        }

        PlannerActionPlan enrichedPlan = rectifyPlanForDeterministicReferences(query, history, enrichPlan(query, history, plan, traces), traces);
        ExecutionEnvelope execution = executePlan(query, history, family, enrichedPlan, traces);
        return respond(execution.result(), traces, traces.size(), execution.status(), enrichedPlan, execution.executionResult(), execution.pendingClarification());
    }

    private PlannerActionPlan plan(String query, List<ConversationTurn> history, String family, List<ToolTrace> traces) {
        String historyText = formatHistory(history);
        String prompt = """
                You are a planning model for a deterministic local assistant.
                Today is %s.
                Task family is already determined as %s.
                Return strict JSON only with keys:
                taskFamily, action, targetEntityType, targetEntityId, targetLookup, arguments, needsClarification, clarificationQuestion, confidence.
                Use supported email actions: create_email_draft, update_email_draft, schedule_email_draft, send_email_draft, delete_email_draft.
                Use supported calendar actions: create_calendar_item, update_calendar_item, delete_calendar_item.
                For metadata use lookup_metadata. For hardware use search_hardware. For rag use answer_rag. For records use query_records.
                targetLookup may include: referenceText, recipientName, recipientEmail, titleLike, participantName, date, time, itemType, draftReference.
                For records queries, use targetEntityType calendar_item or email_draft, targetEntityId for an explicit record UUID, and arguments such as recordType, itemType, date, referenceText, sortBy, sortDirection, limit, includeDetails.
                For email draft creation or update, include senderName in arguments when the user specifies who the sender is.
                arguments must contain only concrete fields relevant to execution.
                If information is missing or ambiguous, set needsClarification true and provide clarificationQuestion.
                Conversation history:
                %s
                User request: %s
                """.formatted(LocalDate.now(), family, historyText, query);

        try {
            String raw = ollamaClient.generateJson(prompt);
            addTrace(traces, "PlannerJSONRaw", prompt, raw, true, 0);
            Map<String, Object> rawPlan = objectMapper.readValue(extractJson(raw), new TypeReference<>() {});
            PlannerActionPlan plan = normalizePlannerPlan(rawPlan, query, family);
            addTrace(traces, "PlannerJSONNormalized", prompt, toJson(plan), plan != null, 0);
            boolean valid = plan != null && family.equalsIgnoreCase(plan.taskFamily()) && !isBlank(plan.action());
            addTrace(traces, "PlannerValidation", toJson(Map.of("family", family)), toJson(Map.of("valid", valid, "action", plan == null ? null : plan.action())), valid, 0);
            return valid ? plan : null;
        } catch (Exception exception) {
            addTrace(traces, "PlannerValidation", family, exception.getMessage(), false, 0);
            return null;
        }
    }

    private PlannerActionPlan enrichPlan(String query, List<ConversationTurn> history, PlannerActionPlan plan, List<ToolTrace> traces) {
        if ("calendar".equalsIgnoreCase(plan.taskFamily()) && "create_calendar_item".equals(plan.action())) {
            return enrichCalendarCreatePlan(query, plan);
        }
        if (!"email".equalsIgnoreCase(plan.taskFamily())) {
            return plan;
        }
        String inferredSender = inferSenderName(query, history);
        Map<String, Object> arguments = new LinkedHashMap<>(plan.arguments() == null ? Map.of() : plan.arguments());
        String senderName = stringArg(arguments, "senderName");
        if ((isBlank(senderName) || looksGenericSender(senderName)) && !isBlank(inferredSender)) {
            arguments.put("senderName", inferredSender);
            plan = new PlannerActionPlan(
                    plan.taskFamily(),
                    plan.action(),
                    plan.targetEntityType(),
                    plan.targetEntityId(),
                    plan.targetLookup(),
                    arguments,
                    plan.needsClarification(),
                    plan.clarificationQuestion(),
                    plan.confidence()
            );
        }
        if ("create_email_draft".equals(plan.action())) {
            if (isBlank(stringArg(plan.arguments(), "senderName"))) {
                return new PlannerActionPlan(
                        plan.taskFamily(),
                        plan.action(),
                        plan.targetEntityType(),
                        plan.targetEntityId(),
                        plan.targetLookup(),
                        plan.arguments(),
                        true,
                        "Who should the sender be for that email?",
                        plan.confidence()
                );
            }
            return addGeneratedEmailContent(query, history, plan, null, traces);
        }
        if ("update_email_draft".equals(plan.action()) && requiresRewrite(plan)) {
            EmailDraftRecord draft = resolveSingleDraft(plan);
            if (draft != null) {
                return addGeneratedEmailContent(query, history, plan, draft, traces);
            }
        }
        return plan;
    }

    private PlannerActionPlan rectifyPlanForDeterministicReferences(String query, List<ConversationTurn> history, PlannerActionPlan plan, List<ToolTrace> traces) {
        if (plan == null) {
            return null;
        }
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        boolean draftReference = EMAIL_DRAFT_REFERENCE_PATTERN.matcher(lower).find();
        boolean eventReference = EVENT_ID_REFERENCE_PATTERN.matcher(lower).find();
        boolean readOnlyDraftQuestion = draftReference && looksLikeReadOnlyRecordQuery(lower);
        boolean readOnlyEventQuestion = eventReference && looksLikeReadOnlyRecordQuery(lower);

        if ((readOnlyDraftQuestion || readOnlyEventQuestion) && ("lookup_metadata".equals(plan.action()) || "lookup_metadata".equalsIgnoreCase(plan.taskFamily()))) {
            PlannerActionPlan rectified = buildReferenceLookupPlan(query, draftReference ? "email_draft" : "calendar_item");
            addTrace(traces, "PlannerReferenceRectification", toJson(plan), toJson(rectified), true, 0);
            return rectified;
        }
        return plan;
    }

    private PlannerActionPlan buildReferenceLookupPlan(String query, String recordType) {
        String recordId = extractUuid(query);
        String itemType = "calendar_item".equals(recordType) ? inferItemType(query) : null;
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("recordType", recordType);
        arguments.put("includeDetails", true);
        arguments.put("limit", 1);
        if ("calendar_item".equals(recordType) && !isBlank(itemType)) {
            arguments.put("itemType", itemType);
        }
        return new PlannerActionPlan(
                "records",
                "query_records",
                recordType,
                recordId,
                new PlannerLookup(
                        sanitizeRecordReference(query, firstNonBlank(extractQuotedText(query), extractTitleReference(query))),
                        null,
                        null,
                        extractTitleReference(query),
                        null,
                        inferRelativeDate(query),
                        inferTime(query),
                        itemType,
                        "email_draft".equals(recordType) ? recordId : null
                ),
                arguments,
                false,
                null,
                0.9
        );
    }

    private PlannerActionPlan enrichCalendarCreatePlan(String query, PlannerActionPlan plan) {
        Map<String, Object> arguments = new LinkedHashMap<>(plan.arguments() == null ? Map.of() : plan.arguments());
        PlannerLookup lookup = plan.targetLookup();
        if (isBlank(stringArg(arguments, "title"))) {
            String inferredTitle = firstNonBlank(
                    extractQuotedText(query),
                    extractTitleReference(query),
                    lookup == null ? null : lookup.titleLike()
            );
            if (!isBlank(inferredTitle)) {
                arguments.put("title", inferredTitle);
            }
        }
        if (isBlank(stringArg(arguments, "date"))) {
            String inferredDate = inferRelativeDate(query);
            if (!isBlank(inferredDate)) {
                arguments.put("date", inferredDate);
            } else if (lookup != null && !isBlank(lookup.date())) {
                arguments.put("date", lookup.date());
            }
        }
        if (isBlank(stringArg(arguments, "time"))) {
            String inferredTime = inferTime(query);
            if (!isBlank(inferredTime)) {
                arguments.put("time", inferredTime);
            } else if (lookup != null && !isBlank(lookup.time())) {
                arguments.put("time", lookup.time());
            }
        }
        return new PlannerActionPlan(
                plan.taskFamily(),
                plan.action(),
                plan.targetEntityType(),
                plan.targetEntityId(),
                plan.targetLookup(),
                arguments,
                plan.needsClarification(),
                plan.clarificationQuestion(),
                plan.confidence()
        );
    }

    private ExecutionEnvelope executePlan(String query, List<ConversationTurn> history, String family, PlannerActionPlan plan, List<ToolTrace> traces) {
        if ("metadata".equals(family)) {
            String uuid = coalesce(plan.targetEntityId(), stringArg(plan.arguments(), "uuid"));
            return new ExecutionEnvelope(
                    executeDirect(query, history, traces, "MetadataLookupTool", Map.of("uuid", uuid), () -> toJson(toolsClient.metadata(new MetadataLookupRequest(uuid)))),
                    "COMPLETED",
                    null,
                    null
            );
        }
        if ("hardware".equals(family)) {
            String deviceName = coalesce(stringArg(plan.arguments(), "deviceName"), stringArg(plan.arguments(), "query"));
            return new ExecutionEnvelope(
                    executeDirect(query, history, traces, "HardwareInventoryTool", Map.of("deviceName", deviceName), () -> toJson(toolsClient.hardware(new HardwareInventoryRequest(deviceName)))),
                    "COMPLETED",
                    null,
                    null
            );
        }
        if ("rag".equals(family)) {
            String raw = executeDirect(query, history, traces, "RAGRetrievalTool", Map.of("queryText", query), () -> toJson(toolsClient.rag(new RagRetrievalRequest(query))));
            return new ExecutionEnvelope(finalizeAnswer(query, history, summarizeOrLlm(raw, query), traces), "COMPLETED", null, null);
        }
        if ("records".equals(family) || "query_records".equals(plan.action())) {
            RecordLookupRequest request = buildRecordLookupRequest(query, plan);
            RecordLookupResponse response = trace("RecordLookupTool", toJson(request), traces, () -> toolsClient.lookupRecords(request));
            if (response == null) {
                return new ExecutionEnvelope("Record lookup failed.", "VALIDATION_ERROR", null, null);
            }
            return new ExecutionEnvelope(renderRecordLookupResult(query, response), "COMPLETED", null, null);
        }

        PlannerExecutionResult result = trace("ToolExecution", toJson(plan), traces, () -> toolsClient.executePlan(plan));
        if (result == null) {
            return new ExecutionEnvelope("Tool execution failed.", "VALIDATION_ERROR", null, null);
        }
        if (result.clarificationNeeded()) {
            addTrace(traces, "ClarificationResult", toJson(plan), toJson(result), true, 0);
            PendingClarification pending = new PendingClarification(plan.taskFamily(), plan.action(), result.clarificationQuestion(), plan, result.candidateIds(), result.candidateSummaries());
            return new ExecutionEnvelope(result.clarificationQuestion(), "CLARIFICATION", result, pending);
        }
        if ("VALIDATION_ERROR".equalsIgnoreCase(result.status())) {
            addTrace(traces, "ValidationResult", toJson(plan), toJson(result), false, 0);
            return new ExecutionEnvelope(result.message(), "VALIDATION_ERROR", result, null);
        }
        addTrace(traces, "ValidationResult", toJson(plan), toJson(result), true, 0);
        return new ExecutionEnvelope(renderExecutionResult(result), "COMPLETED", result, null);
    }

    private String executeDirect(String query, List<ConversationTurn> history, List<ToolTrace> traces, String tool, Object input, ToolCall<String> call) {
        String raw = trace(tool, toJson(input), traces, call);
        return finalizeAnswer(query, history, summarizeOrLlm(raw, query), traces);
    }

    private PlannerActionPlan addGeneratedEmailContent(String query, List<ConversationTurn> history, PlannerActionPlan plan, EmailDraftRecord existingDraft, List<ToolTrace> traces) {
        Map<String, Object> arguments = new LinkedHashMap<>(plan.arguments() == null ? Map.of() : plan.arguments());
        String historyText = formatHistory(history);
        String prompt = existingDraft == null
                ? """
                Write a grounded email draft as strict JSON with keys subject, body, tone.
                Do not invent dates, times, platforms, rooms, or placeholders.
                Do not add a greeting or sign-off.
                Conversation history:
                %s
                Sender name: %s
                Respect the user's request.
                User request: %s
                """.formatted(historyText, stringArg(arguments, "senderName"), query)
                : """
                Rewrite this existing email as strict JSON with keys subject, body, tone.
                Preserve facts already present. Do not invent dates, times, platforms, rooms, or placeholders.
                Do not add a greeting or sign-off.
                Conversation history:
                %s
                Sender name: %s
                Existing draft:
                subject: %s
                body: %s
                tone: %s
                User request: %s
                """.formatted(historyText, coalesce(stringArg(arguments, "senderName"), existingDraft.senderName()), existingDraft.subject(), existingDraft.body(), existingDraft.tone(), query);
        try {
            String raw = ollamaClient.generateJson(prompt);
            addTrace(traces, "EmailGeneration", prompt, raw, true, 0);
            Map<String, Object> generated = objectMapper.readValue(extractJson(raw), new TypeReference<>() {});
            String subject = objectAsString(generated.get("subject"));
            String body = sanitizeGeneratedEmailBody(objectAsString(generated.get("body")));
            if (!isValidGeneratedEmail(subject, body, query, history, existingDraft)) {
                addTrace(traces, "EmailGenerationValidation", prompt, "Rejected ungrounded generated email content.", false, 0);
                return plan;
            }
            arguments.putIfAbsent("subject", subject);
            arguments.put("body", body);
            arguments.put("tone", generated.getOrDefault("tone", arguments.getOrDefault("tone", "professional")));
            return new PlannerActionPlan(plan.taskFamily(), plan.action(), plan.targetEntityType(), plan.targetEntityId(), plan.targetLookup(), arguments, false, null, plan.confidence());
        } catch (Exception exception) {
            addTrace(traces, "EmailGeneration", prompt, exception.getMessage(), false, 0);
            return plan;
        }
    }

    private EmailDraftRecord resolveSingleDraft(PlannerActionPlan plan) {
        EmailDraftsResponse response = toolsClient.emailDrafts();
        if (response == null || response.drafts() == null) {
            return null;
        }
        List<EmailDraftRecord> matches = response.drafts().stream()
                .filter(draft -> matchesDraft(draft, plan))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private boolean matchesDraft(EmailDraftRecord draft, PlannerActionPlan plan) {
        if (!isBlank(plan.targetEntityId()) && draft.id().equals(plan.targetEntityId())) {
            return true;
        }
        PlannerLookup lookup = plan.targetLookup();
        if (lookup == null) {
            return false;
        }
        return contains(draft.subject(), lookup.titleLike())
                || contains(draft.subject(), lookup.referenceText())
                || contains(draft.body(), lookup.referenceText())
                || contains(draft.recipient(), lookup.recipientEmail())
                || contains(draft.recipient(), lookup.recipientName())
                || contains(draft.recipient(), lookup.referenceText());
    }

    private boolean requiresRewrite(PlannerActionPlan plan) {
        Map<String, Object> arguments = plan.arguments() == null ? Map.of() : plan.arguments();
        return !isBlank(stringArg(arguments, "rewriteInstruction"))
                || !isBlank(stringArg(arguments, "rewrite"))
                || "true".equalsIgnoreCase(stringArg(arguments, "makeItShorter"))
                || "true".equalsIgnoreCase(stringArg(arguments, "shorten"))
                || (isBlank(stringArg(arguments, "body")) && !isBlank(stringArg(arguments, "tone")));
    }

    private String renderExecutionResult(PlannerExecutionResult result) {
        if (result.emailDraft() != null) {
            return switch (result.appliedPlan().action()) {
                case "create_email_draft" -> "Email draft created: subject \"%s\", recipient %s.".formatted(result.emailDraft().subject(), result.emailDraft().recipient());
                case "update_email_draft" -> "Email draft updated: subject \"%s\", recipient %s.".formatted(result.emailDraft().subject(), result.emailDraft().recipient());
                case "schedule_email_draft" -> "Email draft scheduled: subject \"%s\", scheduled for %s.".formatted(result.emailDraft().subject(), result.emailDraft().scheduledFor());
                case "send_email_draft" -> "Email draft sent: subject \"%s\", recipient %s.".formatted(result.emailDraft().subject(), result.emailDraft().recipient());
                case "delete_email_draft" -> "Email draft deleted: subject \"%s\".".formatted(result.emailDraft().subject());
                default -> result.message();
            };
        }
        if (result.calendarItem() != null) {
            return switch (result.appliedPlan().action()) {
                case "create_calendar_item", "update_calendar_item" -> "%s: %s \"%s\" on %s at %s.".formatted(
                        "create_calendar_item".equals(result.appliedPlan().action()) ? "Calendar item created" : "Calendar item updated",
                        result.calendarItem().itemType(),
                        result.calendarItem().title(),
                        result.calendarItem().date(),
                        result.calendarItem().time());
                case "delete_calendar_item" -> "Deleted calendar item \"%s\".".formatted(result.calendarItem().title());
                default -> result.message();
            };
        }
        return result.message();
    }

    private RecordLookupRequest buildRecordLookupRequest(String query, PlannerActionPlan plan) {
        PlannerLookup lookup = plan.targetLookup();
        Map<String, Object> arguments = plan.arguments() == null ? Map.of() : plan.arguments();
        String explicitId = firstNonBlank(plan.targetEntityId(), extractUuid(query), stringArg(arguments, "recordId"));
        String targetType = firstNonBlank(plan.targetEntityType(), stringArg(arguments, "recordType"));
        String recordType = normalizeRecordTypeHint(targetType, lookup == null ? null : lookup.itemType(), query);
        String itemType = "calendar_item".equals(recordType)
                ? firstNonBlank(stringArg(arguments, "itemType"), lookup == null ? null : lookup.itemType(), inferItemType(query))
                : null;
        String date = firstNonBlank(stringArg(arguments, "date"), lookup == null ? null : lookup.date(), inferRelativeDate(query));
        String referenceText = sanitizeRecordReference(query, firstNonBlank(
                stringArg(arguments, "referenceText"),
                stringArg(arguments, "title"),
                stringArg(arguments, "subject"),
                lookup == null ? null : lookup.titleLike(),
                extractQuotedText(query)
        ));
        String sortBy = firstNonBlank(stringArg(arguments, "sortBy"), "calendar_item".equals(recordType) ? "time" : "updated_at");
        String sortDirection = firstNonBlank(stringArg(arguments, "sortDirection"), "desc");
        Integer limit = parseInteger(stringArg(arguments, "limit"));
        boolean includeDetails = truthy(arguments.get("includeDetails")) || wantsDetailedLookup(query) || explicitId != null;
        return new RecordLookupRequest(query, recordType, itemType, explicitId, date, referenceText, sortBy, sortDirection, limit, includeDetails);
    }

    private String renderRecordLookupResult(String query, RecordLookupResponse response) {
        if (response.primaryMatch() == null) {
            return "I couldn't find a matching persisted record for that request.";
        }
        RecordLookupMatch match = response.primaryMatch();
        if ("calendar_item".equals(match.recordType())) {
            return renderCalendarLookupResult(query, response, match);
        }
        return renderEmailLookupResult(response, match);
    }

    private String renderCalendarLookupResult(String query, RecordLookupResponse response, RecordLookupMatch match) {
        String itemLabel = isBlank(match.itemType()) ? "calendar item" : match.itemType().toLowerCase(Locale.ROOT).replace('_', ' ');
        StringBuilder answer = new StringBuilder();
        if (isLatestLookup(query)) {
            answer.append("The last ").append(itemLabel);
            if (!isBlank(response.date())) {
                answer.append(" for ").append(response.date());
            }
            answer.append(" was \"").append(match.title()).append("\"");
            if (!isBlank(match.time())) {
                answer.append(" at ").append(match.time());
            }
            answer.append('.');
        } else {
            answer.append("I found the ").append(itemLabel).append(" \"").append(match.title()).append("\"");
            if (!isBlank(match.date())) {
                answer.append(" on ").append(match.date());
            }
            if (!isBlank(match.time())) {
                answer.append(" at ").append(match.time());
            }
            answer.append('.');
        }
        if (response.includeDetails() && !isBlank(match.notes())) {
            answer.append(" Notes: ").append(match.notes()).append('.');
        }
        answer.append(" Record ID: ").append(match.recordId()).append('.');
        return answer.toString();
    }

    private String renderEmailLookupResult(RecordLookupResponse response, RecordLookupMatch match) {
        String query = response.queryText() == null ? "" : response.queryText().toLowerCase(Locale.ROOT);
        if (query.contains("recipient")) {
            return "The current recipients for draft ID %s are %s.".formatted(
                    match.recordId(),
                    isBlank(match.recipient()) ? "(none)" : match.recipient()
            );
        }
        StringBuilder answer = new StringBuilder("I found the email draft \"")
                .append(match.subject())
                .append("\"");
        if (!isBlank(match.recipient())) {
            answer.append(" for ").append(match.recipient());
        }
        answer.append('.');
        if (response.includeDetails() && !isBlank(match.content())) {
            answer.append(" Body: ").append(match.content()).append('.');
        }
        answer.append(" Draft ID: ").append(match.recordId()).append('.');
        return answer.toString();
    }

    private String summarizeOrLlm(String raw, String query) {
        try {
            if (raw != null && raw.trim().startsWith("{\"draft\"")) {
                return raw;
            }
            String llm = ollamaClient.summarize(query, raw);
            return isBlank(llm) || looksLikeJson(llm) ? raw : llm;
        } catch (Exception ignored) {
            return raw;
        }
    }

    private String finalizeAnswer(String query, List<ConversationTurn> history, String draftAnswer, List<ToolTrace> traces) {
        addTrace(traces, "FinalAnswerGeneration", query, draftAnswer, true, 0);
        return draftAnswer;
    }

    private String respondChat(String query, List<ConversationTurn> history, List<ToolTrace> traces) {
        String prompt = """
                You are a strict conversational assistant inside a local planner-backed productivity app.
                You may respond helpfully to greetings, casual conversation, capability questions, clarification, and explanation requests.
                Stay anchored to the main product surface: email and calendar planning workflows.
                Internal-only tooling such as metadata lookup, hardware lookup, and document retrieval should not be presented as primary conversational capabilities.
                Do not pretend you have general internet access or arbitrary capabilities.
                If the user asks casually about internal-only tooling, explain that the main conversational surface focuses on email and calendar workflows and redirect them there.
                Keep the answer concise and conversational.
                Conversation history:
                %s
                User message: %s
                """.formatted(formatHistory(history), query);
        try {
            String answer = ollamaClient.generate(prompt);
            if (isBlank(answer) || looksLikeJson(answer)) {
                answer = "I can chat about this app and help with email and calendar workflows. Tell me what you want to do.";
            }
            addTrace(traces, "ChatResponse", prompt, answer, true, 0);
            return answer;
        } catch (Exception exception) {
            addTrace(traces, "ChatResponse", prompt, exception.getMessage(), false, 0);
            return "I can chat about this app and help with email and calendar workflows. Tell me what you want to do.";
        }
    }

    String detectTaskFamily(String lower) {
        return determineTaskFamily(normalizeRequest(lower, List.of(), null));
    }

    String detectTaskFamily(String lower, List<ConversationTurn> history) {
        return determineTaskFamily(normalizeRequest(lower, history, null));
    }

    String detectTaskFamily(String lower, List<ConversationTurn> history, PendingClarification pendingClarification) {
        return determineTaskFamily(normalizeRequest(lower, history, pendingClarification));
    }

    private NormalizedRequest normalizeRequest(String query, List<ConversationTurn> history, PendingClarification pendingClarification) {
        String original = query == null ? "" : query.trim();
        String lower = original.toLowerCase(Locale.ROOT);
        String draftId = EMAIL_DRAFT_REFERENCE_PATTERN.matcher(lower).find() ? extractUuid(original) : null;
        String eventId = EVENT_ID_REFERENCE_PATTERN.matcher(lower).find() ? extractUuid(original) : null;
        String uuid = extractUuid(original);
        boolean readOnly = looksLikeReadOnlyRecordQuery(lower);
        boolean mutation = MUTATION_PATTERN.matcher(lower).find();
        return new NormalizedRequest(
                original,
                lower,
                pendingClarification,
                draftId,
                eventId,
                uuid,
                readOnly,
                mutation,
                extractEmailAddresses(original),
                inferRelativeDate(original),
                inferTime(original),
                inferItemType(original),
                firstNonBlank(extractQuotedText(original), extractTitleReference(original)),
                determineTaskFamily(lower, history, pendingClarification, draftId, eventId, uuid, readOnly, mutation)
        );
    }

    private String determineTaskFamily(NormalizedRequest normalized) {
        return normalized.family();
    }

    private String determineTaskFamily(String lower, List<ConversationTurn> history, PendingClarification pendingClarification,
                                       String draftId, String eventId, String uuid, boolean readOnly, boolean mutation) {
        if (pendingClarification != null && !isBlank(pendingClarification.taskFamily())
                && (lower.length() <= 80 || FOLLOW_UP_PATTERN.matcher(lower).find() || looksLikeNameOnlyResponse(lower, history))) {
            return pendingClarification.taskFamily();
        }
        boolean metadataWord = METADATA_PATTERN.matcher(lower).find();
        boolean hardware = HARDWARE_PATTERN.matcher(lower).find();
        boolean rag = RAG_PATTERN.matcher(lower).find();
        boolean recordQuery = RECORD_QUERY_PATTERN.matcher(lower).find();
        boolean recordEntity = RECORD_ENTITY_PATTERN.matcher(lower).find();
        boolean email = EMAIL_PATTERN.matcher(lower).find();
        boolean calendar = CALENDAR_PATTERN.matcher(lower).find();
        boolean emailAction = EMAIL_ACTION_PATTERN.matcher(lower).find();
        boolean calendarAction = CALENDAR_ACTION_PATTERN.matcher(lower).find();
        boolean strongEmailIntent = STRONG_EMAIL_INTENT_PATTERN.matcher(lower).find();
        boolean strongCalendarIntent = STRONG_CALENDAR_INTENT_PATTERN.matcher(lower).find();
        boolean ragAction = RAG_ACTION_PATTERN.matcher(lower).find();
        boolean eventLike = EVENT_TIME_PATTERN.matcher(lower).find() && EVENT_PARTICIPANT_PATTERN.matcher(lower).find();
        boolean calendarContinuation = isCalendarContinuation(lower, history);
        boolean emailContinuation = isEmailContinuation(lower, history);
        boolean recordsContinuation = isRecordsContinuation(lower, history);
        boolean metadata = (metadataWord && !recordEntity)
                || (uuid != null && draftId == null && eventId == null && !emailAction && !calendarAction && !emailContinuation && !calendarContinuation && !recordEntity);

        if (metadata) {
            return "metadata";
        }
        if (hardware) {
            return "hardware";
        }
        if (rag && ragAction && !emailAction && !calendarAction) {
            return "rag";
        }
        if (draftId != null && readOnly) {
            return "records";
        }
        if (eventId != null && readOnly) {
            return "records";
        }
        if (draftId != null) {
            return "email";
        }
        if (eventId != null) {
            return "calendar";
        }
        if (recordQuery && recordEntity && !mutation) {
            return "records";
        }
        if (recordQuery && lower.contains("email") && lower.contains("draft") && !mutation) {
            return "records";
        }
        if (recordQuery && lower.contains("calendar") && (lower.contains("reminder") || lower.contains("event") || lower.contains("task")) && !mutation) {
            return "records";
        }
        if (emailAction && calendarAction) {
            if (emailContinuation) {
                return "email";
            }
            if (strongEmailIntent && !strongCalendarIntent) {
                return "email";
            }
            if (strongCalendarIntent && !strongEmailIntent) {
                return "calendar";
            }
            return "clarification";
        }
        if (emailAction && !calendarAction) {
            return "email";
        }
        if (calendarAction && !emailAction) {
            return "calendar";
        }
        if (eventLike && !emailAction) {
            return "calendar";
        }
        if (email && calendar) {
            if (emailAction) {
                return "email";
            }
            if (calendarAction) {
                return "calendar";
            }
            return "clarification";
        }
        if (recordQuery && email && !mutation) {
            return "records";
        }
        if (recordQuery && calendar && !mutation) {
            return "records";
        }
        if (email) {
            return "email";
        }
        if (calendar) {
            return "calendar";
        }
        if (recordQuery && (recordEntity || uuid != null) && !mutation) {
            return "records";
        }
        if (rag) {
            return "rag";
        }
        if (recordsContinuation) {
            return "records";
        }
        if (calendarContinuation) {
            return "calendar";
        }
        if (emailContinuation) {
            return "email";
        }
        if (isGeneralChatPrompt(lower)) {
            return "chat";
        }
        return "unsupported";
    }

    private String outOfScopeResponse() {
        return "That request is outside the main conversational scope. Supported workflows here are email and calendar planning.";
    }

    private <T> T trace(String tool, String input, List<ToolTrace> traces, ToolCall<T> call) {
        Instant start = Instant.now();
        try {
            T output = call.execute();
            addTrace(traces, tool, input, toJson(output), true, Duration.between(start, Instant.now()).toMillis());
            return output;
        } catch (Exception exception) {
            addTrace(traces, tool, input, exception.getMessage(), false, Duration.between(start, Instant.now()).toMillis());
            return null;
        }
    }

    private void addTrace(List<ToolTrace> traces, String tool, String input, String output, boolean success, long durationMs) {
        traces.add(new ToolTrace(tool, input, output, success, durationMs));
    }

    private String extractJson(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }

    private String stringArg(Map<String, Object> arguments, String key) {
        if (arguments == null) {
            return null;
        }
        Object value = arguments.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private boolean contains(String value, String search) {
        return !isBlank(value) && !isBlank(search) && value.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private boolean looksLikeJson(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private String formatHistory(List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (ConversationTurn turn : history) {
            if (turn == null || isBlank(turn.content())) {
                continue;
            }
            String role = isBlank(turn.role()) ? "unknown" : turn.role().trim();
            builder.append(role).append(": ").append(turn.content().trim()).append('\n');
        }
        return builder.isEmpty() ? "(none)" : builder.toString().trim();
    }

    private ContextSelection selectContext(String query, List<ConversationTurn> history, List<ToolTrace> traces) {
        if (history == null || history.isEmpty()) {
            ContextSelection selection = new ContextSelection("current_only", List.of());
            addTrace(traces, "ContextSelection", query, toJson(Map.of("mode", selection.mode(), "turnCount", 0)), true, 0);
            return selection;
        }

        String heuristicMode = heuristicContextMode(query, history);
        if (!"llm_fallback".equals(heuristicMode)) {
            List<ConversationTurn> selected = applyContextMode(heuristicMode, history);
            addTrace(traces, "ContextSelection", query, toJson(Map.of("mode", heuristicMode, "turnCount", selected.size(), "source", "heuristic")), true, 0);
            return new ContextSelection(heuristicMode, selected);
        }

        String preview = formatHistory(applyContextMode("recent_turns", history));
        try {
            String raw = ollamaClient.selectContextMode(query, preview);
            String mode = parseContextMode(raw);
            List<ConversationTurn> selected = applyContextMode(mode, history);
            addTrace(traces, "ContextSelection", query, toJson(Map.of("mode", mode, "turnCount", selected.size(), "source", "llm_fallback")), true, 0);
            return new ContextSelection(mode, selected);
        } catch (Exception exception) {
            List<ConversationTurn> selected = applyContextMode("recent_turns", history);
            addTrace(traces, "ContextSelection", query, toJson(Map.of("mode", "recent_turns", "turnCount", selected.size(), "source", "llm_fallback_default", "error", exception.getMessage())), false, 0);
            return new ContextSelection("recent_turns", selected);
        }
    }

    private String heuristicContextMode(String query, List<ConversationTurn> history) {
        String lower = query.toLowerCase(Locale.ROOT);
        if (FOLLOW_UP_PATTERN.matcher(lower).find()) {
            return "recent_turns";
        }
        if (!history.isEmpty() && (query.length() <= 48 || lower.startsWith("yes") || lower.startsWith("no") || lower.contains("default title"))) {
            return "recent_turns";
        }
        if (!SELF_CONTAINED_PATTERN.matcher(lower).find()) {
            return "llm_fallback";
        }
        if (history.size() > 12 && (lower.startsWith("what about") || lower.startsWith("and ") || lower.startsWith("also "))) {
            return "full_history";
        }
        if (lower.contains("same thread") || lower.contains("full context")) {
            return "full_history";
        }
        return "current_only";
    }

    private List<ConversationTurn> applyContextMode(String mode, List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return switch (mode) {
            case "full_history" -> history;
            case "recent_turns" -> history.subList(Math.max(0, history.size() - RECENT_TURN_LIMIT), history.size());
            default -> List.of();
        };
    }

    private String parseContextMode(String raw) throws JsonProcessingException {
        Map<String, Object> parsed = objectMapper.readValue(extractJson(raw), new TypeReference<>() {});
        Object value = parsed.get("mode");
        if (value == null) {
            return "recent_turns";
        }
        String mode = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "current_only", "recent_turns", "full_history" -> mode;
            default -> "recent_turns";
        };
    }

    private boolean isGeneralChatPrompt(String lower) {
        if (isBlank(lower)) {
            return false;
        }
        if (CHAT_PATTERN.matcher(lower).find()) {
            return true;
        }
        return lower.contains("?") && !TASK_REQUEST_PATTERN.matcher(lower).find();
    }

    private String coalesce(String left, String right) {
        return isBlank(left) ? right : left;
    }

    private boolean isCalendarContinuation(String lower, List<ConversationTurn> history) {
        return !isBlank(lower)
                && CALENDAR_FOLLOW_UP_PATTERN.matcher(lower).find()
                && recentHistoryContains(history, "calendar");
    }

    private boolean isEmailContinuation(String lower, List<ConversationTurn> history) {
        return !isBlank(lower)
                && (EMAIL_FOLLOW_UP_PATTERN.matcher(lower).find()
                || TIME_ONLY_FOLLOW_UP_PATTERN.matcher(lower.trim()).matches()
                || looksLikeNameOnlyResponse(lower, history))
                && recentHistoryContains(history, "email");
    }

    private boolean isRecordsContinuation(String lower, List<ConversationTurn> history) {
        return !isBlank(lower)
                && (FOLLOW_UP_PATTERN.matcher(lower).find()
                || lower.contains("check again")
                || lower.contains("that one")
                || UUID_PATTERN.matcher(lower).find())
                && recentHistoryContains(history, "records");
    }

    private boolean recentHistoryContains(List<ConversationTurn> history, String family) {
        if (history == null || history.isEmpty()) {
            return false;
        }
        int start = Math.max(0, history.size() - RECENT_TURN_LIMIT);
        for (int i = start; i < history.size(); i++) {
            ConversationTurn turn = history.get(i);
            if (turn == null || isBlank(turn.content())) {
                continue;
            }
            String content = turn.content().toLowerCase(Locale.ROOT);
            if ("calendar".equals(family) && (content.contains("calendar") || content.contains("task") || content.contains("reminder") || content.contains("meeting"))) {
                return true;
            }
            if ("email".equals(family) && (content.contains("email") || content.contains("draft") || content.contains("@") || content.contains("sender"))) {
                return true;
            }
            if ("records".equals(family) && (content.contains("persisted record")
                    || content.contains("draft id")
                    || content.contains("event id")
                    || content.contains("current recipients")
                    || content.contains("scheduled for"))) {
                return true;
            }
        }
        return false;
    }

    private boolean looksGenericSender(String value) {
        return !isBlank(value) && GENERIC_SENDER_PATTERN.matcher(value.trim()).matches();
    }

    private boolean looksLikeNameOnlyResponse(String lower, List<ConversationTurn> history) {
        if (isBlank(lower) || history == null || history.isEmpty()) {
            return false;
        }
        if (!NAME_ONLY_PATTERN.matcher(toTitleCase(lower.trim())).matches()) {
            return false;
        }
        ConversationTurn last = history.getLast();
        return last != null
                && !isBlank(last.content())
                && last.content().toLowerCase(Locale.ROOT).contains("sender");
    }

    private PlannerActionPlan heuristicFallbackPlan(NormalizedRequest normalized, List<ConversationTurn> history) {
        String query = normalized.originalQuery();
        String family = normalized.family();
        if ("email".equalsIgnoreCase(family)) {
            String draftId = normalized.draftId();
            if (!isBlank(draftId) && normalized.readOnlyRecordQuery()) {
                return buildReferenceLookupPlan(query, "email_draft");
            }
            String sender = firstNonBlank(inferSenderName(query, history), extractStandaloneName(query));
            String recipientName = extractNamedRecipient(query);
            List<String> recipientEmails = normalized.recipientEmails();
            if (!isBlank(draftId)) {
                Map<String, Object> arguments = new LinkedHashMap<>();
                if (!recipientEmails.isEmpty()) {
                    arguments.put("recipientEmails", recipientEmails);
                }
                if (!isBlank(sender) && !contains(query, "sender does not change")) {
                    arguments.put("senderName", sender);
                }
                return new PlannerActionPlan(
                        "email",
                        "update_email_draft",
                        "email_draft",
                        draftId,
                        new PlannerLookup(null, null, null, extractSubjectHint(query), null, null, null, null, draftId),
                        arguments,
                        recipientEmails.isEmpty() && recipientName == null,
                        recipientEmails.isEmpty() && recipientName == null ? "Which recipient should I use for that email?" : null,
                        0.55
                );
            }
            if (recipientName == null) {
                return null;
            }
            Map<String, Object> arguments = new LinkedHashMap<>();
            if (!recipientEmails.isEmpty()) {
                arguments.put("recipientEmails", recipientEmails);
            }
            if (!isBlank(sender)) {
                arguments.put("senderName", sender);
            }
            return new PlannerActionPlan("email", "create_email_draft", "email", null,
                    new PlannerLookup(null, recipientName, null, extractSubjectHint(query), null, null, null, null, null),
                    arguments, isBlank(sender), isBlank(sender) ? "Who should the sender be for that email?" : null, 0.5);
        }
        if ("calendar".equalsIgnoreCase(family)) {
            String eventId = normalized.eventId();
            if (!isBlank(eventId) && normalized.readOnlyRecordQuery()) {
                return buildReferenceLookupPlan(query, "calendar_item");
            }
            String title = firstNonBlank(normalized.referenceText(), extractQuotedText(query), extractTitleReference(query));
            String date = normalized.date();
            String time = normalized.time();
            Map<String, Object> arguments = new LinkedHashMap<>();
            putIfNotBlank(arguments, "title", title);
            putIfNotBlank(arguments, "date", date);
            putIfNotBlank(arguments, "time", time);
            if (!isBlank(eventId)) {
                return new PlannerActionPlan("calendar", "update_calendar_item", "calendarItem", eventId,
                        new PlannerLookup(null, null, null, title, extractParticipantName(query), date, time, normalized.itemType(), null),
                        arguments, false, null, 0.55);
            }
            return new PlannerActionPlan("calendar", "create_calendar_item", "calendarItem", null,
                    new PlannerLookup(null, null, null, title, extractParticipantName(query), date, time, null, null),
                    arguments, false, null, 0.5);
        }
        if ("records".equalsIgnoreCase(family)) {
            String recordId = normalized.uuid();
            String itemType = normalized.itemType();
            String recordType = normalizeRecordTypeHint(null, itemType, query);
            String date = normalized.date();
            String referenceText = normalized.referenceText();
            Map<String, Object> arguments = new LinkedHashMap<>();
            putIfNotBlank(arguments, "recordType", recordType);
            putIfNotBlank(arguments, "itemType", itemType);
            putIfNotBlank(arguments, "date", date);
            putIfNotBlank(arguments, "referenceText", referenceText);
            arguments.put("sortBy", "calendar_item".equals(recordType) ? "time" : "updated_at");
            arguments.put("sortDirection", "desc");
            arguments.put("limit", 1);
            arguments.put("includeDetails", wantsDetailedLookup(query) || !isBlank(recordId));
            return new PlannerActionPlan(
                    "records",
                    "query_records",
                    recordType,
                    recordId,
                    new PlannerLookup(referenceText, null, null, referenceText, null, date, null, itemType, null),
                    arguments,
                    false,
                    null,
                    0.5
            );
        }
        return null;
    }

    private void putIfNotBlank(Map<String, Object> arguments, String key, String value) {
        if (!isBlank(value)) {
            arguments.put(key, value);
        }
    }

    private String extractQuotedText(String query) {
        if (isBlank(query)) {
            return null;
        }
        Matcher matcher = QUOTED_TEXT_PATTERN.matcher(query);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String extractTitleReference(String query) {
        if (isBlank(query)) {
            return null;
        }
        Matcher matcher = TITLE_REFERENCE_PATTERN.matcher(query);
        if (!matcher.find()) {
            return null;
        }
        return firstNonBlank(matcher.group(1), matcher.group(2));
    }

    private String inferRelativeDate(String query) {
        if (isBlank(query)) {
            return null;
        }
        String explicit = inferExplicitDate(query);
        if (!isBlank(explicit)) {
            return explicit;
        }
        String weekday = inferWeekdayDate(query);
        if (!isBlank(weekday)) {
            return weekday;
        }
        if (TOMORROW_PATTERN.matcher(query).find()) {
            return LocalDate.now().plusDays(1).toString();
        }
        if (TODAY_PATTERN.matcher(query).find()) {
            return LocalDate.now().toString();
        }
        return null;
    }

    private String inferTime(String query) {
        if (isBlank(query)) {
            return null;
        }
        Matcher matcher = TIME_PATTERN.matcher(query);
        if (!matcher.find()) {
            return null;
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2));
        String meridiem = matcher.group(3);
        if (meridiem != null) {
            meridiem = meridiem.toLowerCase(Locale.ROOT);
            if ("pm".equals(meridiem) && hour < 12) {
                hour += 12;
            } else if ("am".equals(meridiem) && hour == 12) {
                hour = 0;
            }
        }
        return "%02d:%02d".formatted(hour, minute);
    }

    private String inferExplicitDate(String query) {
        Matcher matcher = EXPLICIT_DATE_PATTERN.matcher(query);
        if (!matcher.find()) {
            return null;
        }
        int month = switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
            case "january" -> 1;
            case "february" -> 2;
            case "march" -> 3;
            case "april" -> 4;
            case "may" -> 5;
            case "june" -> 6;
            case "july" -> 7;
            case "august" -> 8;
            case "september" -> 9;
            case "october" -> 10;
            case "november" -> 11;
            case "december" -> 12;
            default -> 0;
        };
        if (month == 0) {
            return null;
        }
        int day = Integer.parseInt(matcher.group(2));
        int year = matcher.group(3) == null ? LocalDate.now().getYear() : Integer.parseInt(matcher.group(3));
        return LocalDate.of(year, month, day).toString();
    }

    private String inferWeekdayDate(String query) {
        Matcher matcher = WEEKDAY_PATTERN.matcher(query);
        if (!matcher.find()) {
            return null;
        }
        DayOfWeek target = switch (matcher.group(1).toLowerCase(Locale.ROOT)) {
            case "monday" -> DayOfWeek.MONDAY;
            case "tuesday" -> DayOfWeek.TUESDAY;
            case "wednesday" -> DayOfWeek.WEDNESDAY;
            case "thursday" -> DayOfWeek.THURSDAY;
            case "friday" -> DayOfWeek.FRIDAY;
            case "saturday" -> DayOfWeek.SATURDAY;
            case "sunday" -> DayOfWeek.SUNDAY;
            default -> null;
        };
        if (target == null) {
            return null;
        }
        LocalDate today = LocalDate.now();
        int delta = (target.getValue() - today.getDayOfWeek().getValue() + 7) % 7;
        if (delta == 0) {
            delta = 7;
        }
        return today.plusDays(delta).toString();
    }

    private String extractNamedRecipient(String query) {
        if (isBlank(query)) {
            return null;
        }
        Matcher matcher = Pattern.compile("\\bto\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*)", Pattern.CASE_INSENSITIVE).matcher(query);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private String extractParticipantName(String query) {
        if (isBlank(query)) {
            return null;
        }
        Matcher matcher = EVENT_PARTICIPANT_PATTERN.matcher(query);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group().replaceFirst("(?i)^with\\s+", "").trim();
    }

    private String extractSubjectHint(String query) {
        if (isBlank(query)) {
            return null;
        }
        if (query.toLowerCase(Locale.ROOT).contains("check-in")) {
            return "check-in";
        }
        return extractQuotedText(query);
    }

    private String extractUuid(String query) {
        if (isBlank(query)) {
            return null;
        }
        Matcher matcher = UUID_PATTERN.matcher(query);
        return matcher.find() ? matcher.group().trim() : null;
    }

    private String extractStandaloneName(String query) {
        if (isBlank(query)) {
            return null;
        }
        String normalized = toTitleCase(query.trim());
        return NAME_ONLY_PATTERN.matcher(normalized).matches() ? normalized.replace(".", "").trim() : null;
    }

    private List<String> extractEmailAddresses(String query) {
        if (isBlank(query)) {
            return List.of();
        }
        List<String> emails = new ArrayList<>();
        Matcher matcher = EMAIL_ADDRESS_CAPTURE_PATTERN.matcher(query);
        while (matcher.find()) {
            String email = matcher.group().trim().toLowerCase(Locale.ROOT);
            if (!emails.contains(email)) {
                emails.add(email);
            }
        }
        return emails;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String inferSenderName(String query, List<ConversationTurn> history) {
        Optional<String> fromQuery = extractSenderName(query);
        if (fromQuery.isPresent()) {
            return fromQuery.get();
        }
        List<ConversationTurn> ordered = history == null ? List.of() : history;
        for (int i = ordered.size() - 1; i >= 0; i--) {
            ConversationTurn turn = ordered.get(i);
            if (turn == null || isBlank(turn.content()) || !"user".equalsIgnoreCase(turn.role())) {
                continue;
            }
            Optional<String> extracted = extractSenderName(turn.content());
            if (extracted.isPresent()) {
                return extracted.get();
            }
        }
        return null;
    }

    private Optional<String> extractSenderName(String text) {
        if (isBlank(text)) {
            return Optional.empty();
        }
        var parenMatcher = PAREN_SENDER_PATTERN.matcher(text);
        if (parenMatcher.find()) {
            return Optional.ofNullable(parenMatcher.group(1)).map(String::trim).filter(name -> !name.isBlank());
        }
        var matcher = EXPLICIT_SENDER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matcher.group(1)).map(String::trim).filter(name -> !name.isBlank());
    }

    private PlannerActionPlan continuePendingClarification(String query, List<ConversationTurn> history, PendingClarification pendingClarification, List<ToolTrace> traces) {
        if (pendingClarification == null || pendingClarification.plan() == null) {
            return null;
        }
        PlannerActionPlan plan = pendingClarification.plan();
        Map<String, Object> arguments = new LinkedHashMap<>(plan.arguments() == null ? Map.of() : plan.arguments());
        if ("email".equalsIgnoreCase(plan.taskFamily())) {
            List<String> recipientEmails = extractEmailAddresses(query);
            if (!recipientEmails.isEmpty()) {
                arguments.put("recipientEmails", recipientEmails);
                PlannerActionPlan continued = new PlannerActionPlan(
                        plan.taskFamily(),
                        plan.action(),
                        plan.targetEntityType(),
                        firstNonBlank(plan.targetEntityId(), extractUuid(query)),
                        plan.targetLookup(),
                        arguments,
                        false,
                        null,
                        plan.confidence()
                );
                addTrace(traces, "PendingClarificationContinuation", query, toJson(continued), true, 0);
                return continued;
            }
            String senderName = firstNonBlank(extractStandaloneName(query), inferSenderName(query, history));
            if (!isBlank(senderName)) {
                arguments.put("senderName", senderName);
                PlannerActionPlan continued = new PlannerActionPlan(plan.taskFamily(), plan.action(), plan.targetEntityType(), plan.targetEntityId(), plan.targetLookup(), arguments, false, null, plan.confidence());
                addTrace(traces, "PendingClarificationContinuation", query, toJson(continued), true, 0);
                return continued;
            }
        }
        if ("calendar".equalsIgnoreCase(plan.taskFamily())) {
            PlannerLookup lookup = plan.targetLookup();
            PlannerLookup mergedLookup = new PlannerLookup(
                    firstNonBlank(lookup == null ? null : lookup.referenceText(), query),
                    lookup == null ? null : lookup.recipientName(),
                    lookup == null ? null : lookup.recipientEmail(),
                    firstNonBlank(extractTitleReference(query), extractQuotedText(query), lookup == null ? null : lookup.titleLike()),
                    firstNonBlank(extractParticipantName(query), lookup == null ? null : lookup.participantName()),
                    firstNonBlank(inferRelativeDate(query), lookup == null ? null : lookup.date()),
                    firstNonBlank(inferTime(query), lookup == null ? null : lookup.time()),
                    firstNonBlank(inferItemType(query), lookup == null ? null : lookup.itemType()),
                    lookup == null ? null : lookup.draftReference()
            );
            PlannerActionPlan continued = new PlannerActionPlan(plan.taskFamily(), plan.action(), plan.targetEntityType(), plan.targetEntityId(), mergedLookup, arguments, false, null, plan.confidence());
            addTrace(traces, "PendingClarificationContinuation", query, toJson(continued), true, 0);
            return continued;
        }
        if ("records".equalsIgnoreCase(plan.taskFamily())) {
            String recordType = normalizeRecordTypeHint(plan.targetEntityType(), plan.targetLookup() == null ? null : plan.targetLookup().itemType(), query);
            PlannerActionPlan continued = buildReferenceLookupPlan(query, recordType);
            addTrace(traces, "PendingClarificationContinuation", query, toJson(continued), true, 0);
            return continued;
        }
        return null;
    }

    private PlannerActionPlan normalizePlannerPlan(Map<String, Object> raw, String query, String family) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Object lookupValue = raw.get("targetLookup");
        PlannerLookup lookup = null;
        if (lookupValue instanceof Map<?, ?> lookupMap) {
            lookup = objectMapper.convertValue(lookupMap, PlannerLookup.class);
        } else if (lookupValue instanceof String lookupMarker && !lookupMarker.isBlank()) {
            lookup = normalizeLookupMarker(lookupMarker, raw, query);
        } else if (lookupValue != null) {
            return null;
        }

        Map<String, Object> arguments = new LinkedHashMap<>();
        if (raw.get("arguments") instanceof Map<?, ?> argumentMap) {
            argumentMap.forEach((key, value) -> arguments.put(String.valueOf(key), value));
        }
        normalizePlannerArguments(arguments, lookup, query);
        String targetEntityId = firstNonBlank(objectAsString(raw.get("targetEntityId")), extractUuid(query));
        return new PlannerActionPlan(
                firstNonBlank(objectAsString(raw.get("taskFamily")), family),
                objectAsString(raw.get("action")),
                objectAsString(raw.get("targetEntityType")),
                targetEntityId,
                lookup,
                arguments,
                truthy(raw.get("needsClarification")),
                objectAsString(raw.get("clarificationQuestion")),
                raw.get("confidence") instanceof Number number ? number.doubleValue() : 0.0
        );
    }

    private PlannerLookup normalizeLookupMarker(String lookupMarker, Map<String, Object> raw, String query) {
        String targetEntityId = objectAsString(raw.get("targetEntityId"));
        return switch (lookupMarker) {
            case "titleLike" -> new PlannerLookup(query, null, null, firstNonBlank(extractTitleReference(query), extractQuotedText(query)), extractParticipantName(query), inferRelativeDate(query), inferTime(query), inferItemType(query), null);
            case "draftReference" -> new PlannerLookup(query, null, null, extractSubjectHint(query), null, null, null, null, targetEntityId);
            case "itemType" -> new PlannerLookup(query, null, null, extractTitleReference(query), extractParticipantName(query), inferRelativeDate(query), inferTime(query), inferItemType(query), null);
            default -> new PlannerLookup(query, null, null, extractTitleReference(query), extractParticipantName(query), inferRelativeDate(query), inferTime(query), inferItemType(query), targetEntityId);
        };
    }

    private void normalizePlannerArguments(Map<String, Object> arguments, PlannerLookup lookup, String query) {
        putIfNotBlank(arguments, "itemType", firstNonBlank(stringArg(arguments, "itemType"), stringArg(arguments, "taskType"), inferItemType(query), lookup == null ? null : lookup.itemType()));
        putIfNotBlank(arguments, "title", firstNonBlank(stringArg(arguments, "title"), stringArg(arguments, "summary"), extractTitleReference(query), extractQuotedText(query), lookup == null ? null : lookup.titleLike()));
        putIfNotBlank(arguments, "recordType", firstNonBlank(stringArg(arguments, "recordType"), normalizeRecordTypeHint(null, lookup == null ? null : lookup.itemType(), query)));
        putIfNotBlank(arguments, "referenceText", firstNonBlank(stringArg(arguments, "referenceText"), extractQuotedText(query), extractTitleReference(query), lookup == null ? null : lookup.referenceText(), lookup == null ? null : lookup.titleLike()));
        if (isBlank(stringArg(arguments, "date"))) {
            putIfNotBlank(arguments, "date", firstNonBlank(extractDatePart(stringArg(arguments, "dateTime")), extractDatePart(stringArg(arguments, "startTime")), extractDatePart(stringArg(arguments, "startDate")), inferRelativeDate(query), lookup == null ? null : lookup.date()));
        }
        if (isBlank(stringArg(arguments, "time"))) {
            putIfNotBlank(arguments, "time", firstNonBlank(extractTimePart(stringArg(arguments, "dateTime")), extractTimePart(stringArg(arguments, "startTime")), extractTimePart(stringArg(arguments, "startDate")), inferTime(query), lookup == null ? null : lookup.time()));
        }
        if (isBlank(stringArg(arguments, "rewriteInstruction"))) {
            if (truthy(arguments.get("makeItShorter")) || truthy(arguments.get("shorten")) || contains(query, "shorter")) {
                arguments.put("rewriteInstruction", "make it shorter");
            } else {
                putIfNotBlank(arguments, "rewriteInstruction", firstNonBlank(stringArg(arguments, "rewriteInstruction"), stringArg(arguments, "rewrite")));
            }
        }
        if (!arguments.containsKey("recipientEmails")) {
            List<String> emails = extractEmailAddresses(query);
            if (!emails.isEmpty()) {
                arguments.put("recipientEmails", emails);
            }
        }
    }

    private String inferItemType(String query) {
        if (isBlank(query)) {
            return null;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.contains("reminder")) {
            return "REMINDER";
        }
        if (lower.contains(" task")) {
            return "TASK";
        }
        if (lower.contains("meeting") || lower.contains("event")) {
            return "MEETING";
        }
        return null;
    }

    private String normalizeRecordTypeHint(String targetEntityType, String itemType, String query) {
        String entity = firstNonBlank(targetEntityType, itemType);
        if (!isBlank(entity)) {
            String normalized = entity.toLowerCase(Locale.ROOT);
            if (normalized.contains("draft") || normalized.contains("email")) {
                return "email_draft";
            }
            if (normalized.contains("calendar") || normalized.contains("event") || normalized.contains("reminder") || normalized.contains("task") || normalized.contains("meeting")) {
                return "calendar_item";
            }
        }
        String lower = query == null ? "" : query.toLowerCase(Locale.ROOT);
        if (lower.contains("draft") || lower.contains("email draft")) {
            return "email_draft";
        }
        if (lower.contains("reminder") || lower.contains("task") || lower.contains("meeting") || lower.contains("event")) {
            return "calendar_item";
        }
        return "all";
    }

    private String sanitizeRecordReference(String query, String value) {
        if (isBlank(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase(query == null ? "" : query.trim())) {
            return null;
        }
        return trimmed;
    }

    private Integer parseInteger(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean wantsDetailedLookup(String query) {
        if (isBlank(query)) {
            return false;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.contains("detail") || lower.contains("details") || lower.contains("tell me more") || lower.contains("more about");
    }

    private boolean isLatestLookup(String query) {
        if (isBlank(query)) {
            return false;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.contains("last") || lower.contains("latest") || lower.contains("most recent");
    }

    private boolean looksLikeReadOnlyRecordQuery(String lower) {
        if (isBlank(lower)) {
            return false;
        }
        boolean readSignal = RECORD_QUERY_PATTERN.matcher(lower).find()
                || lower.startsWith("what time")
                || lower.startsWith("when ")
                || lower.contains("scheduled for");
        return readSignal && !MUTATION_PATTERN.matcher(lower).find();
    }

    private String extractDatePart(String value) {
        if (isBlank(value) || !value.contains("T")) {
            return null;
        }
        return value.substring(0, value.indexOf('T'));
    }

    private String extractTimePart(String value) {
        if (isBlank(value) || !value.contains("T")) {
            return null;
        }
        String time = value.substring(value.indexOf('T') + 1);
        return time.length() >= 5 ? time.substring(0, 5) : time;
    }

    private boolean isValidGeneratedEmail(String subject, String body, String query, List<ConversationTurn> history, EmailDraftRecord existingDraft) {
        if (isBlank(subject) || isBlank(body)) {
            return false;
        }
        String combined = subject + "\n" + body;
        if (PLACEHOLDER_PATTERN.matcher(combined).find()) {
            return false;
        }
        String context = query + "\n" + formatHistory(history) + "\n" + (existingDraft == null ? "" : existingDraft.subject() + "\n" + existingDraft.body());
        boolean scheduleProvided = TIME_PATTERN.matcher(context).find() || TODAY_PATTERN.matcher(context).find() || TOMORROW_PATTERN.matcher(context).find();
        if (!scheduleProvided && TIME_PATTERN.matcher(combined).find()) {
            return false;
        }
        return !PLATFORM_PATTERN.matcher(combined).find();
    }

    private String sanitizeGeneratedEmailBody(String body) {
        if (isBlank(body)) {
            return body;
        }
        return body
                .replaceAll("(?i)^\\s*(hi|hello)\\s+[^,]+,\\s*", "")
                .replaceAll("(?is)\\n*(best regards|regards|best),?\\s*\\n.*$", "")
                .trim();
    }

    private String objectAsString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean truthy(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }

    private String toTitleCase(String value) {
        if (isBlank(value)) {
            return value;
        }
        String[] parts = value.replace(".", "").trim().split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }

    private AgentQueryResponse respond(String result, List<ToolTrace> traces, int iterations, String status, PlannerActionPlan normalizedPlan, PlannerExecutionResult executionResult, PendingClarification pendingClarification) {
        return new AgentQueryResponse(result, traces, iterations, status, normalizedPlan, executionResult, pendingClarification);
    }

    @FunctionalInterface
    private interface ToolCall<T> {
        T execute();
    }

    private record ExecutionEnvelope(String result, String status, PlannerExecutionResult executionResult, PendingClarification pendingClarification) {
    }

    private record ContextSelection(String mode, List<ConversationTurn> history) {
    }

    private record NormalizedRequest(
            String originalQuery,
            String lowerQuery,
            PendingClarification pendingClarification,
            String draftId,
            String eventId,
            String uuid,
            boolean readOnlyRecordQuery,
            boolean mutation,
            List<String> recipientEmails,
            String date,
            String time,
            String itemType,
            String referenceText,
            String family
    ) {
    }
}
