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
import java.util.regex.Pattern;

@Service
public class PlannerAgentService {

    private static final Pattern UUID_PATTERN = Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b(email|e-mail|draft|reply|send|subject|tone)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALENDAR_PATTERN = Pattern.compile("\\b(calendar|meeting|event|reminder|task|schedule|reschedule|move|cancel)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern METADATA_PATTERN = Pattern.compile("\\b(metadata|uuid)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HARDWARE_PATTERN = Pattern.compile("\\b(hardware|device|workstation|laptop|desktop|specs?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAG_PATTERN = Pattern.compile("\\b(docs?|documents?|knowledge|architecture|rag|reference|manual)\\b", Pattern.CASE_INSENSITIVE);

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
        List<ToolTrace> traces = new ArrayList<>();
        if (query.isBlank()) {
            return new AgentQueryResponse("Please provide a request.", traces, 0);
        }

        String family = detectTaskFamily(query.toLowerCase(Locale.ROOT));
        addTrace(traces, "PlannerFamilySelection", query, "{\"taskFamily\":\"" + family + "\"}", true, 0);
        if ("unsupported".equals(family)) {
            return new AgentQueryResponse(outOfScopeResponse(), traces, traces.size());
        }

        PlannerActionPlan plan = plan(query, family, traces);
        if (plan == null) {
            return new AgentQueryResponse("I couldn't build a valid action plan for that request.", traces, traces.size());
        }
        if (plan.needsClarification()) {
            String answer = finalizeAnswer(query, plan.clarificationQuestion(), traces);
            return new AgentQueryResponse(answer, traces, traces.size());
        }

        PlannerActionPlan enrichedPlan = enrichPlan(query, plan, traces);
        String answer = executePlan(query, family, enrichedPlan, traces);
        return new AgentQueryResponse(answer, traces, traces.size());
    }

    private PlannerActionPlan plan(String query, String family, List<ToolTrace> traces) {
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
                arguments must contain only concrete fields relevant to execution.
                If information is missing or ambiguous, set needsClarification true and provide clarificationQuestion.
                User request: %s
                """.formatted(LocalDate.now(), family, query);

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

    private PlannerActionPlan enrichPlan(String query, PlannerActionPlan plan, List<ToolTrace> traces) {
        if (!"email".equalsIgnoreCase(plan.taskFamily())) {
            return plan;
        }
        if ("create_email_draft".equals(plan.action())) {
            return addGeneratedEmailContent(query, plan, null, traces);
        }
        if ("update_email_draft".equals(plan.action()) && requiresRewrite(plan)) {
            EmailDraftRecord draft = resolveSingleDraft(plan);
            if (draft != null) {
                return addGeneratedEmailContent(query, plan, draft, traces);
            }
        }
        return plan;
    }

    private String executePlan(String query, String family, PlannerActionPlan plan, List<ToolTrace> traces) {
        if ("metadata".equals(family)) {
            String uuid = coalesce(plan.targetEntityId(), stringArg(plan.arguments(), "uuid"));
            return executeDirect(query, traces, "MetadataLookupTool", Map.of("uuid", uuid), () -> toJson(toolsClient.metadata(new MetadataLookupRequest(uuid))));
        }
        if ("hardware".equals(family)) {
            String deviceName = coalesce(stringArg(plan.arguments(), "deviceName"), stringArg(plan.arguments(), "query"));
            return executeDirect(query, traces, "HardwareInventoryTool", Map.of("deviceName", deviceName), () -> toJson(toolsClient.hardware(new HardwareInventoryRequest(deviceName))));
        }
        if ("rag".equals(family)) {
            String raw = executeDirect(query, traces, "RAGRetrievalTool", Map.of("queryText", query), () -> toJson(toolsClient.rag(new RagRetrievalRequest(query))));
            return finalizeAnswer(query, summarizeOrLlm(raw, query), traces);
        }

        PlannerExecutionResult result = trace("ToolExecution", toJson(plan), traces, () -> toolsClient.executePlan(plan));
        if (result == null) {
            return finalizeAnswer(query, "Tool execution failed.", traces);
        }
        if (result.clarificationNeeded()) {
            addTrace(traces, "ClarificationResult", toJson(plan), toJson(result), true, 0);
            return finalizeAnswer(query, result.clarificationQuestion(), traces);
        }
        if ("VALIDATION_ERROR".equalsIgnoreCase(result.status())) {
            addTrace(traces, "ValidationResult", toJson(plan), toJson(result), false, 0);
            return finalizeAnswer(query, result.message(), traces);
        }
        addTrace(traces, "ValidationResult", toJson(plan), toJson(result), true, 0);
        String summary = summarizeExecutionResult(result);
        return finalizeAnswer(query, summary, traces);
    }

    private String executeDirect(String query, List<ToolTrace> traces, String tool, Object input, ToolCall<String> call) {
        String raw = trace(tool, toJson(input), traces, call);
        return finalizeAnswer(query, summarizeOrLlm(raw, query), traces);
    }

    private PlannerActionPlan addGeneratedEmailContent(String query, PlannerActionPlan plan, EmailDraftRecord existingDraft, List<ToolTrace> traces) {
        Map<String, Object> arguments = new LinkedHashMap<>(plan.arguments() == null ? Map.of() : plan.arguments());
        String prompt = existingDraft == null
                ? """
                Write a natural email draft as strict JSON with keys subject, body, tone.
                Respect the user's request.
                User request: %s
                """.formatted(query)
                : """
                Rewrite this existing email as strict JSON with keys subject, body, tone.
                Existing draft:
                subject: %s
                body: %s
                tone: %s
                User request: %s
                """.formatted(existingDraft.subject(), existingDraft.body(), existingDraft.tone(), query);
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

    private String finalizeAnswer(String query, String draftAnswer, List<ToolTrace> traces) {
        String prompt = "User request: " + query + "\nBackend result: " + draftAnswer + "\nReturn a concise user-facing answer.";
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

    private String detectTaskFamily(String lower) {
        boolean email = EMAIL_PATTERN.matcher(lower).find();
        boolean calendar = CALENDAR_PATTERN.matcher(lower).find();
        boolean metadata = METADATA_PATTERN.matcher(lower).find() || UUID_PATTERN.matcher(lower).find();
        boolean hardware = HARDWARE_PATTERN.matcher(lower).find();
        boolean rag = RAG_PATTERN.matcher(lower).find();
        int matches = (email ? 1 : 0) + (calendar ? 1 : 0) + (metadata ? 1 : 0) + (hardware ? 1 : 0) + (rag ? 1 : 0);
        if (matches != 1) {
            return "unsupported";
        }
        if (email) return "email";
        if (calendar) return "calendar";
        if (metadata) return "metadata";
        if (hardware) return "hardware";
        return "rag";
    }

    private String outOfScopeResponse() {
        return "That request is outside the current planner scope. Supported families are email, calendar, metadata, hardware, and document Q&A.";
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

    private String coalesce(String left, String right) {
        return isBlank(left) ? right : left;
    }

    @FunctionalInterface
    private interface ToolCall<T> {
        T execute();
    }
}
