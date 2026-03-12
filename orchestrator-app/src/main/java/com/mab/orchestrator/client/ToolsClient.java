package com.mab.orchestrator.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mab.shared.model.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ToolsClient {
    private final RestTemplate restTemplate;
    private final String toolsBaseUrl;
    private final ObjectMapper objectMapper;
    private final Map<String, ToolCallback> toolCallbacks;

    public ToolsClient(RestTemplate restTemplate,
                       ObjectMapper objectMapper,
                       @Qualifier("mcpToolCallbacks") ToolCallbackProvider toolCallbackProvider,
                       @Value("${tools.base-url:http://localhost:8082}") String toolsBaseUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.toolsBaseUrl = toolsBaseUrl;
        this.toolCallbacks = Arrays.stream(toolCallbackProvider.getToolCallbacks())
                .collect(Collectors.toMap(callback -> callback.getToolDefinition().name(), callback -> callback));
    }

    public EmailToolResponse email(EmailToolRequest request) {
        return invokeTool("draft_email", request, EmailToolResponse.class);
    }

    public CalendarToolResponse calendar(CalendarToolRequest request) {
        return invokeTool("create_calendar_item", request, CalendarToolResponse.class);
    }

    public MetadataLookupResponse metadata(MetadataLookupRequest request) {
        return invokeTool("lookup_metadata", request, MetadataLookupResponse.class);
    }

    public HardwareInventoryResponse hardware(HardwareInventoryRequest request) {
        return invokeTool("search_hardware", request, HardwareInventoryResponse.class);
    }

    public RagRetrievalResponse rag(RagRetrievalRequest request) {
        return invokeTool("retrieve_rag_documents", request, RagRetrievalResponse.class);
    }

    public CalendarItemsResponse calendarItems() {
        return get("/api/calendar/items", CalendarItemsResponse.class);
    }

    public EmailDraftsResponse emailDrafts() {
        return get("/api/email/drafts", EmailDraftsResponse.class);
    }

    public PlannerExecutionResult executePlan(PlannerActionPlan request) {
        return post("/api/planner/execute", request, PlannerExecutionResult.class);
    }

    public ContactsResponse contacts() {
        return get("/api/contacts", ContactsResponse.class);
    }

    public ContactRecord upsertContact(ContactUpsertRequest request) {
        return post("/api/contacts", request, ContactRecord.class);
    }

    public SystemStateResponse systemState() {
        return get("/api/state", SystemStateResponse.class);
    }

    public SystemStateResponse selectGenerationModel(ModelSelectionRequest request) {
        return restTemplate.exchange(
                toolsBaseUrl + "/api/state/model",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(request),
                SystemStateResponse.class
        ).getBody();
    }

    private <T> T invokeTool(String toolName, Object request, Class<T> responseType) {
        ToolCallback callback = toolCallbacks.get(toolName);
        if (callback == null) {
            throw new IllegalStateException("MCP tool not available: " + toolName);
        }
        try {
            String payload = toToolPayload(callback, request);
            String raw = callback.call(payload);
            String normalized = normalizeMcpResponse(raw);
            return objectMapper.readValue(normalized, responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to invoke MCP tool " + toolName, e);
        }
    }

    private String toToolPayload(ToolCallback callback, Object request) throws JsonProcessingException {
        JsonNode requestNode = objectMapper.valueToTree(request);
        JsonNode schema = objectMapper.readTree(callback.getToolDefinition().inputSchema());
        JsonNode properties = schema.path("properties");
        if (properties.isObject() && properties.size() == 1) {
            String singleProperty = properties.fieldNames().next();
            return objectMapper.writeValueAsString(Map.of(singleProperty, requestNode));
        }
        return objectMapper.writeValueAsString(request);
    }

    private String normalizeMcpResponse(String raw) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(raw);
        if (node.isArray() && !node.isEmpty()) {
            JsonNode first = node.get(0);
            if (first.hasNonNull("text")) {
                return first.get("text").asText();
            }
        }
        return raw;
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        return restTemplate.postForObject(toolsBaseUrl + path, body, responseType);
    }

    private <T> T get(String path, Class<T> responseType) {
        return restTemplate.getForObject(toolsBaseUrl + path, responseType);
    }
}
