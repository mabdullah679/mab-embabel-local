package com.mab.tools.mcp;

import com.mab.shared.model.*;
import com.mab.tools.service.ToolsService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class AgentTools {

    private final ToolsService toolsService;

    public AgentTools(ToolsService toolsService) {
        this.toolsService = toolsService;
    }

    @Tool(name = "draft_email", description = "Draft an email for one or more recipients.")
    public EmailToolResponse draftEmail(EmailToolRequest request) {
        return toolsService.generateEmail(request);
    }

    @Tool(name = "create_calendar_item", description = "Create a meeting, task, or reminder in the local calendar.")
    public CalendarToolResponse createCalendarItem(CalendarToolRequest request) {
        return toolsService.createCalendarEvent(request);
    }

    @Tool(name = "lookup_metadata", description = "Look up metadata by UUID.")
    public MetadataLookupResponse lookupMetadata(MetadataLookupRequest request) {
        return toolsService.lookupMetadata(request);
    }

    @Tool(name = "search_hardware", description = "Search hardware inventory by device name or class.")
    public HardwareInventoryResponse searchHardware(HardwareInventoryRequest request) {
        return toolsService.searchHardware(request);
    }

    @Tool(name = "retrieve_rag_documents", description = "Retrieve the most relevant seeded documents for a question.")
    public RagRetrievalResponse retrieveRagDocuments(RagRetrievalRequest request) {
        return toolsService.retrieveRag(request);
    }

    @Tool(name = "lookup_records", description = "Query persisted calendar items and email drafts by date, type, title, subject, or record id.")
    public RecordLookupResponse lookupRecords(RecordLookupRequest request) {
        return toolsService.lookupRecords(request);
    }
}
