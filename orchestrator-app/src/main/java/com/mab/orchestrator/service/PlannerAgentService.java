package com.mab.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mab.orchestrator.client.OllamaClient;
import com.mab.orchestrator.client.ToolsClient;
import com.mab.shared.model.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class PlannerAgentService {
    private static final int RECENT_TURN_LIMIT = 4;
    private static final Pattern EVENT_TIME_PATTERN = Pattern.compile("\\b(today|tomorrow|tonight|next\\s+\\w+|at\\s+\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?|\\d{1,2}(?::\\d{2})?\\s*(?:am|pm))\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EVENT_PARTICIPANT_PATTERN = Pattern.compile("\\bwith\\s+[a-z][a-z\\s.'&-]*", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPLICIT_SENDER_PATTERN = Pattern.compile("\\b(?:my name is|i am|i'm|from)\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,3})\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern UUID_PATTERN = Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b(email|e-mail|draft|reply|send|subject|tone)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALENDAR_PATTERN = Pattern.compile("\\b(calendar|meeting|event|reminder|task|schedule|reschedule|move|cancel|lunch|dinner|coffee|breakfast|check-?in|sync|standup|call|appointment|interview|demo|catch-?up)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern METADATA_PATTERN = Pattern.compile("\\b(metadata|uuid)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HARDWARE_PATTERN = Pattern.compile("\\b(hardware|device|workstation|laptop|desktop|specs?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAG_PATTERN = Pattern.compile("\\b(docs?|documents?|knowledge|architecture|rag|reference|manual)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_ACTION_PATTERN = Pattern.compile("\\b(email|e-mail|draft|reply|send|rewrite|shorten|subject|recipient|body)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALENDAR_ACTION_PATTERN = Pattern.compile("\\b(calendar|meeting|event|reminder|task|schedule|reschedule|move|cancel|book|appointment|lunch|dinner|coffee|breakfast|check-?in|sync|standup|call|interview|demo|catch-?up)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRONG_EMAIL_INTENT_PATTERN = Pattern.compile("\\b(email|e-mail|draft|reply|send|rewrite|shorten)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRONG_CALENDAR_INTENT_PATTERN = Pattern.compile("\\b(calendar|schedule|reschedule|move|cancel|book|appointment)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAG_ACTION_PATTERN = Pattern.compile("\\b(what do|what does|summari[sz]e|explain|tell me|according to|where in)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOLLOW_UP_PATTERN = Pattern.compile("\\b(it|that|them|those|this|previous|above|same|again|shorter|longer|rewrite|send it|schedule it|update it|delete it|use that|instead|also|now)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELF_CONTAINED_PATTERN = Pattern.compile("\\b(email|draft|meeting|calendar|metadata|uuid|hardware|docs?|document|architecture|rag)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHAT_PATTERN = Pattern.compile("\\b(hi|hello|hey|thanks|thank you|who are you|what can you do|how are you|why|can you explain|explain that|tell me more|help me understand|good morning|good afternoon|good evening|sorry)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TASK_REQUEST_PATTERN = Pattern.compile("\\b(create|update|delete|send|schedule|draft|lookup|search|find|book|cancel|move|ingest|write|build)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_SENDER_PATTERN = Pattern.compile("^(the sender|sender|me|myself|from me)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALENDAR_FOLLOW_UP_PATTERN = Pattern.compile("\\b(the one|titled|default title|change the task type|change the type|make it a reminder|make it a task|make it a meeting|reminder|meeting|task)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_FOLLOW_UP_PATTERN = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$|\\b(recipient|sender|send it|schedule it|make it shorter|rewrite it|joe@example|alex@example)\\b", Pattern.CASE_INSENSITIVE);

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
        List<ToolTrace> traces = new ArrayList<>();
        if (query.isBlank()) {
            return new AgentQueryResponse("Please provide a request.", traces, 0);
        }
        ContextSelection context = selectContext(query, rawHistory, traces);
        List<ConversationTurn> history = context.history();

        String family = detectTaskFamily(query.toLowerCase(Locale.ROOT), history);
        addTrace(traces, "PlannerFamilySelection", query, "{\"taskFamily\":\"" + family + "\"}", true, 0);
        if ("clarification".equals(family)) {
            return new AgentQueryResponse("I can help with that, but the request mixes multiple task types. Tell me whether you want an email action or a calendar action first.", traces, traces.size());
        }
        if ("chat".equals(family)) {
            String answer = respondChat(query, history, traces);
            return new AgentQueryResponse(answer, traces, traces.size());
        }
        if ("unsupported".equals(family)) {
            return new AgentQueryResponse(outOfScopeResponse(), traces, traces.size());
        }

        PlannerActionPlan plan = plan(query, history, family, traces);
        if (plan == null) {
            return new AgentQueryResponse("I couldn't build a valid action plan for that request.", traces, traces.size());
        }
        if (plan.needsClarification()) {
            String answer = finalizeAnswer(query, history, plan.clarificationQuestion(), traces);
            return new AgentQueryResponse(answer, traces, traces.size());
        }

        PlannerActionPlan enrichedPlan = enrichPlan(query, history, plan, traces);
        String answer = executePlan(query, history, family, enrichedPlan, traces);
        return new AgentQueryResponse(answer, traces, traces.size());
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
                For metadata use lookup_metadata. For hardware use search_hardware. For rag use answer_rag.
                targetLookup may include: referenceText, recipientName, recipientEmail, titleLike, participantName, date, time, itemType, draftReference.
                For email draft creation or update, include senderName in arguments when the user specifies who the sender is.
                arguments must contain only concrete fields relevant to execution.
                If information is missing or ambiguous, set needsClarification true and provide clarificationQuestion.
                Conversation history:
                %s
                User request: %s
                """.formatted(LocalDate.now(), family, historyText, query);

        try {
            String raw = ollamaClient.generateJson(prompt);
            addTrace(traces, "PlannerJSON", prompt, raw, true, 0);
            PlannerActionPlan plan = objectMapper.readValue(extractJson(raw), PlannerActionPlan.class);
            boolean valid = family.equalsIgnoreCase(plan.taskFamily()) && !isBlank(plan.action());
            addTrace(traces, "PlannerValidation", toJson(Map.of("family", family)), toJson(Map.of("valid", valid, "action", plan.action())), valid, 0);
            return valid ? plan : null;
        } catch (Exception exception) {
            addTrace(traces, "PlannerValidation", family, exception.getMessage(), false, 0);
            return null;
        }
    }

    private PlannerActionPlan enrichPlan(String query, List<ConversationTurn> history, PlannerActionPlan plan, List<ToolTrace> traces) {
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

    private String executePlan(String query, List<ConversationTurn> history, String family, PlannerActionPlan plan, List<ToolTrace> traces) {
        if ("metadata".equals(family)) {
            String uuid = coalesce(plan.targetEntityId(), stringArg(plan.arguments(), "uuid"));
            return executeDirect(query, history, traces, "MetadataLookupTool", Map.of("uuid", uuid), () -> toJson(toolsClient.metadata(new MetadataLookupRequest(uuid))));
        }
        if ("hardware".equals(family)) {
            String deviceName = coalesce(stringArg(plan.arguments(), "deviceName"), stringArg(plan.arguments(), "query"));
            return executeDirect(query, history, traces, "HardwareInventoryTool", Map.of("deviceName", deviceName), () -> toJson(toolsClient.hardware(new HardwareInventoryRequest(deviceName))));
        }
        if ("rag".equals(family)) {
            String raw = executeDirect(query, history, traces, "RAGRetrievalTool", Map.of("queryText", query), () -> toJson(toolsClient.rag(new RagRetrievalRequest(query))));
            return finalizeAnswer(query, history, summarizeOrLlm(raw, query), traces);
        }

        PlannerExecutionResult result = trace("ToolExecution", toJson(plan), traces, () -> toolsClient.executePlan(plan));
        if (result == null) {
            return "Tool execution failed.";
        }
        if (result.clarificationNeeded()) {
            addTrace(traces, "ClarificationResult", toJson(plan), toJson(result), true, 0);
            return result.clarificationQuestion();
        }
        if ("VALIDATION_ERROR".equalsIgnoreCase(result.status())) {
            addTrace(traces, "ValidationResult", toJson(plan), toJson(result), false, 0);
            return result.message();
        }
        addTrace(traces, "ValidationResult", toJson(plan), toJson(result), true, 0);
        String summary = summarizeExecutionResult(result);
        return finalizeAnswer(query, history, summary, traces);
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
                Write a natural email draft as strict JSON with keys subject, body, tone.
                Conversation history:
                %s
                Sender name: %s
                Respect the user's request.
                User request: %s
                """.formatted(historyText, stringArg(arguments, "senderName"), query)
                : """
                Rewrite this existing email as strict JSON with keys subject, body, tone.
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
            arguments.putIfAbsent("subject", generated.get("subject"));
            arguments.put("body", generated.getOrDefault("body", arguments.get("body")));
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
                || (isBlank(stringArg(arguments, "body")) && !isBlank(stringArg(arguments, "tone")));
    }

    private String summarizeExecutionResult(PlannerExecutionResult result) {
        if (result.emailDraft() != null) {
            return switch (result.appliedPlan().action()) {
                case "create_email_draft", "update_email_draft" -> toJson(result.emailDraft());
                case "schedule_email_draft" -> "Scheduled email \"%s\" for %s.".formatted(result.emailDraft().subject(), result.emailDraft().scheduledFor());
                case "send_email_draft" -> "Sent email \"%s\" to %s.".formatted(result.emailDraft().subject(), result.emailDraft().recipient());
                case "delete_email_draft" -> "Deleted email draft \"%s\".".formatted(result.emailDraft().subject());
                default -> result.message();
            };
        }
        if (result.calendarItem() != null) {
            return switch (result.appliedPlan().action()) {
                case "create_calendar_item", "update_calendar_item" -> "%s: %s on %s at %s.".formatted(
                        "create_calendar_item".equals(result.appliedPlan().action()) ? "Calendar item created" : "Calendar item updated",
                        result.calendarItem().title(),
                        result.calendarItem().date(),
                        result.calendarItem().time());
                case "delete_calendar_item" -> "Deleted calendar item \"%s\".".formatted(result.calendarItem().title());
                default -> result.message();
            };
        }
        return result.message();
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
        String prompt = "Conversation history:\n" + formatHistory(history)
                + "\nUser request: " + query
                + "\nBackend result: " + draftAnswer
                + "\nReturn a concise user-facing answer.";
        String answer;
        try {
            answer = ollamaClient.generate(prompt);
            if (isBlank(answer) || looksLikeJson(answer)) {
                answer = draftAnswer;
            }
            addTrace(traces, "FinalAnswerGeneration", prompt, answer, true, 0);
        } catch (Exception exception) {
            answer = draftAnswer;
            addTrace(traces, "FinalAnswerGeneration", prompt, exception.getMessage(), false, 0);
        }
        return answer;
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
        return detectTaskFamily(lower, List.of());
    }

    String detectTaskFamily(String lower, List<ConversationTurn> history) {
        boolean metadata = METADATA_PATTERN.matcher(lower).find() || UUID_PATTERN.matcher(lower).find();
        boolean hardware = HARDWARE_PATTERN.matcher(lower).find();
        boolean rag = RAG_PATTERN.matcher(lower).find();
        boolean email = EMAIL_PATTERN.matcher(lower).find();
        boolean calendar = CALENDAR_PATTERN.matcher(lower).find();
        boolean emailAction = EMAIL_ACTION_PATTERN.matcher(lower).find();
        boolean calendarAction = CALENDAR_ACTION_PATTERN.matcher(lower).find();
        boolean strongEmailIntent = STRONG_EMAIL_INTENT_PATTERN.matcher(lower).find();
        boolean strongCalendarIntent = STRONG_CALENDAR_INTENT_PATTERN.matcher(lower).find();
        boolean ragAction = RAG_ACTION_PATTERN.matcher(lower).find();
        boolean eventLike = EVENT_TIME_PATTERN.matcher(lower).find() && EVENT_PARTICIPANT_PATTERN.matcher(lower).find();

        if (metadata) {
            return "metadata";
        }
        if (hardware) {
            return "hardware";
        }
        if (rag && ragAction && !emailAction && !calendarAction) {
            return "rag";
        }
        if (emailAction && calendarAction) {
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
        if (email) {
            return "email";
        }
        if (calendar) {
            return "calendar";
        }
        if (rag) {
            return "rag";
        }
        if (isCalendarContinuation(lower, history)) {
            return "calendar";
        }
        if (isEmailContinuation(lower, history)) {
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
                && EMAIL_FOLLOW_UP_PATTERN.matcher(lower).find()
                && recentHistoryContains(history, "email");
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
            if ("email".equals(family) && (content.contains("email") || content.contains("draft") || content.contains("@"))) {
                return true;
            }
        }
        return false;
    }

    private boolean looksGenericSender(String value) {
        return !isBlank(value) && GENERIC_SENDER_PATTERN.matcher(value.trim()).matches();
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
        var matcher = EXPLICIT_SENDER_PATTERN.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.ofNullable(matcher.group(1)).map(String::trim).filter(name -> !name.isBlank());
    }

    @FunctionalInterface
    private interface ToolCall<T> {
        T execute();
    }

    private record ContextSelection(String mode, List<ConversationTurn> history) {
    }
}
