package com.domain.orchestrator.service;

import com.domain.orchestrator.client.OllamaClient;
import com.domain.orchestrator.client.ToolsClient;
import com.domain.shared.model.AgentQueryRequest;
import com.domain.shared.model.AgentQueryResponse;
import com.domain.shared.model.ConsumerHealthResponse;
import com.domain.shared.model.ConsumerRecord;
import com.domain.shared.model.PlannerActionPlan;
import com.domain.shared.model.PlannerExecutionResult;
import com.domain.shared.model.ToolTrace;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PlannerAgentService {
    private static final Pattern RECORD_ID_PATTERN = Pattern.compile("\\b(?:record|id)\\s*(?:#|=|:)?\\s*(\\d+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b(\\d+)\\b");
    private static final Pattern TERM_PATTERN = Pattern.compile("[a-z0-9_-]{2,}", Pattern.CASE_INSENSITIVE);

    private final ToolsClient toolsClient;
    private final OllamaClient ollamaClient;

    public PlannerAgentService(ToolsClient toolsClient, OllamaClient ollamaClient) {
        this.toolsClient = toolsClient;
        this.ollamaClient = ollamaClient;
    }

    public AgentQueryResponse execute(AgentQueryRequest request) {
        String query = request.query() == null ? "" : request.query().trim();
        List<ToolTrace> traces = new ArrayList<>();

        if (query.isBlank()) {
            return respond(
                    "Please provide a question about the Spring consumer records.",
                    traces,
                    "VALIDATION_ERROR",
                    null,
                    null
            );
        }

        if (isHealthQuery(query)) {
            return handleHealthQuery(query, traces);
        }

        PlannerActionPlan plan = plan(query);
        addTrace(traces, "RecordQueryPlan", query, toJson(plan), true);

        try {
            List<ConsumerRecord> records = selectRecords(query, traces);
            if (records.isEmpty()) {
                return respond(
                        "I couldn't find any Spring consumer records that matched that request.",
                        traces,
                        "VALIDATION_ERROR",
                        plan,
                        new PlannerExecutionResult(
                                "VALIDATION_ERROR",
                                "No matching records were found.",
                                false,
                                null,
                                "No matching records were found.",
                                plan,
                                null,
                                null,
                                List.of(),
                                List.of()
                        )
                );
            }

            String prompt = buildAnswerPrompt(query, records);
            String answer = ollamaClient.generate(prompt);
            addTrace(traces, "AnswerSynthesis", prompt, answer, true);

            PlannerExecutionResult executionResult = new PlannerExecutionResult(
                    "COMPLETED",
                    "Records retrieved from the Spring consumer API.",
                    false,
                    null,
                    null,
                    plan,
                    null,
                    null,
                    records.stream().map(record -> String.valueOf(record.id())).toList(),
                    records.stream().map(this::recordSummary).toList()
            );

            return respond(answer, traces, "COMPLETED", plan, executionResult);
        } catch (RestClientException exception) {
            addTrace(traces, "SpringConsumerAPI", query, exception.getMessage(), false);
            return respond(
                    "The Spring consumer API is unavailable right now.",
                    traces,
                    "VALIDATION_ERROR",
                    plan,
                    new PlannerExecutionResult(
                            "VALIDATION_ERROR",
                            "The Spring consumer API is unavailable.",
                            false,
                            null,
                            exception.getMessage(),
                            plan,
                            null,
                            null,
                            List.of(),
                            List.of()
                    )
            );
        }
    }

    private AgentQueryResponse handleHealthQuery(String query, List<ToolTrace> traces) {
        ConsumerHealthResponse health = toolsClient.health();
        addTrace(traces, "SpringConsumerHealth", query, toJson(health), true);
        String result = "Spring consumer API health is %s.".formatted(
                health == null || health.status() == null ? "unknown" : health.status()
        );
        return respond(
                result,
                traces,
                "COMPLETED",
                new PlannerActionPlan("records", "check_consumer_health", "consumer_health", null, null, Map.of(), false, null, 1.0),
                new PlannerExecutionResult("COMPLETED", result, false, null, null, null, null, null, List.of(), List.of())
        );
    }

    private PlannerActionPlan plan(String query) {
        Long recordId = extractRecordId(query);
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("query", query);
        if (recordId != null) {
            arguments.put("recordId", recordId);
        }
        return new PlannerActionPlan(
                "records",
                recordId != null ? "read_record" : "search_records",
                "consumer_record",
                recordId == null ? null : String.valueOf(recordId),
                null,
                arguments,
                false,
                null,
                0.95
        );
    }

    private List<ConsumerRecord> selectRecords(String query, List<ToolTrace> traces) {
        Long recordId = extractRecordId(query);
        if (recordId != null) {
            ConsumerRecord record = toolsClient.record(recordId);
            addTrace(traces, "SpringConsumerRecord", String.valueOf(recordId), toJson(record), true);
            return record == null ? List.of() : List.of(record);
        }

        List<ConsumerRecord> allRecords = toolsClient.records();
        addTrace(traces, "SpringConsumerRecords", query, toJson(allRecords), true);
        return rankRecords(query, allRecords);
    }

    private List<ConsumerRecord> rankRecords(String query, List<ConsumerRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        String lowerQuery = query.toLowerCase(Locale.ROOT);
        boolean asksForAll = lowerQuery.contains("all records")
                || lowerQuery.contains("list records")
                || lowerQuery.contains("show records")
                || lowerQuery.contains("api records");
        if (asksForAll) {
            return records.stream().sorted(Comparator.comparing(ConsumerRecord::id)).toList();
        }

        LinkedHashSet<String> terms = new LinkedHashSet<>();
        Matcher matcher = TERM_PATTERN.matcher(lowerQuery);
        while (matcher.find()) {
            terms.add(matcher.group().toLowerCase(Locale.ROOT));
        }

        List<ScoredRecord> scored = records.stream()
                .map(record -> new ScoredRecord(record, scoreRecord(record, terms)))
                .sorted(Comparator.comparingInt(ScoredRecord::score).reversed().thenComparing(item -> item.record().id()))
                .toList();

        if (scored.isEmpty() || scored.get(0).score() <= 0) {
            return records.stream().sorted(Comparator.comparing(ConsumerRecord::id)).toList();
        }

        return scored.stream()
                .filter(item -> item.score() > 0)
                .limit(5)
                .map(ScoredRecord::record)
                .toList();
    }

    private int scoreRecord(ConsumerRecord record, LinkedHashSet<String> terms) {
        int score = 0;
        for (String term : terms) {
            if (record.id() != null && term.equals(String.valueOf(record.id()))) {
                score += 10;
            }
            if (record.lastMessageNumber() != null && term.equals(String.valueOf(record.lastMessageNumber()))) {
                score += 8;
            }
            if (contains(record.sourceName(), term)) {
                score += 5;
            }
            if (contains(record.storedValue(), term)) {
                score += 4;
            }
            if (contains(record.lastMessageText(), term)) {
                score += 6;
            }
            if (contains(record.syncStatus(), term)) {
                score += 3;
            }
            if (contains(record.lastMergedAt(), term)) {
                score += 2;
            }
        }
        return score;
    }

    private String buildAnswerPrompt(String query, List<ConsumerRecord> records) {
        return """
                You answer questions about application data using only the Spring consumer REST API records provided below.
                Do not invent fields or facts that are not present in the records.
                If the data is insufficient, say that plainly.
                Prefer concise answers.

                User question:
                %s

                API records:
                %s
                """.formatted(query, toJson(records));
    }

    private boolean isHealthQuery(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        return lower.contains("health") || lower.contains("consumer status") || lower.equals("status");
    }

    private Long extractRecordId(String query) {
        Matcher explicit = RECORD_ID_PATTERN.matcher(query);
        if (explicit.find()) {
            return Long.parseLong(explicit.group(1));
        }
        if (query.toLowerCase(Locale.ROOT).contains("message")) {
            Matcher number = NUMBER_PATTERN.matcher(query);
            if (number.find()) {
                return Long.parseLong(number.group(1));
            }
        }
        return null;
    }

    private boolean contains(String value, String term) {
        return value != null && term != null && value.toLowerCase(Locale.ROOT).contains(term);
    }

    private String recordSummary(ConsumerRecord record) {
        return "record %s (%s, %s)".formatted(
                record.id(),
                record.sourceName() == null ? "unknown source" : record.sourceName(),
                record.syncStatus() == null ? "unknown status" : record.syncStatus()
        );
    }

    private void addTrace(List<ToolTrace> traces, String tool, String input, String output, boolean success) {
        traces.add(new ToolTrace(tool, input, output, success, 0));
    }

    private String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof List<?> list) {
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < list.size(); i += 1) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(toJson(list.get(i)));
            }
            return builder.append(']').toString();
        }
        if (value instanceof ConsumerRecord record) {
            return """
                    {"id":%s,"sourceName":"%s","storedValue":"%s","lastMessageNumber":%s,"lastMessageText":"%s","lastMergedAt":"%s","syncStatus":"%s"}"""
                    .formatted(
                            numberValue(record.id()),
                            escape(record.sourceName()),
                            escape(record.storedValue()),
                            numberValue(record.lastMessageNumber()),
                            escape(record.lastMessageText()),
                            escape(record.lastMergedAt()),
                            escape(record.syncStatus())
                    );
        }
        if (value instanceof ConsumerHealthResponse health) {
            return "{\"status\":\"%s\"}".formatted(escape(health.status()));
        }
        if (value instanceof PlannerActionPlan plan) {
            return """
                    {"taskFamily":"%s","action":"%s","targetEntityType":"%s","targetEntityId":"%s","arguments":%s}"""
                    .formatted(
                            escape(plan.taskFamily()),
                            escape(plan.action()),
                            escape(plan.targetEntityType()),
                            escape(plan.targetEntityId()),
                            toJson(plan.arguments())
                    );
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(", ");
                }
                first = false;
                builder.append('"').append(escape(String.valueOf(entry.getKey()))).append('"').append(':');
                Object entryValue = entry.getValue();
                if (entryValue instanceof Number || entryValue instanceof Boolean) {
                    builder.append(entryValue);
                } else {
                    builder.append('"').append(escape(String.valueOf(entryValue))).append('"');
                }
            }
            return builder.append('}').toString();
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private String numberValue(Number value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private AgentQueryResponse respond(String result,
                                       List<ToolTrace> traces,
                                       String status,
                                       PlannerActionPlan plan,
                                       PlannerExecutionResult executionResult) {
        return new AgentQueryResponse(result, traces, traces.size(), status, plan, executionResult, null);
    }

    private record ScoredRecord(ConsumerRecord record, int score) {
    }
}
