package com.domain.tools.controller;

import com.domain.shared.model.CalendarItemsResponse;
import com.domain.shared.model.CalendarItemRecord;
import com.domain.shared.model.CalendarItemUpdateRequest;
import com.domain.shared.model.ContactRecord;
import com.domain.shared.model.ContactUpsertRequest;
import com.domain.shared.model.ContactsResponse;
import com.domain.shared.model.EmailDraftRecord;
import com.domain.shared.model.EmailDraftScheduleRequest;
import com.domain.shared.model.EmailDraftUpdateRequest;
import com.domain.shared.model.EmailDraftsResponse;
import com.domain.shared.model.ModelSelectionRequest;
import com.domain.shared.model.PlannerActionPlan;
import com.domain.shared.model.PlannerExecutionResult;
import com.domain.shared.model.RagIngestRequest;
import com.domain.shared.model.RagIngestResponse;
import com.domain.shared.model.SystemStateResponse;
import com.domain.tools.service.PlannerExecutionService;
import com.domain.tools.service.ToolsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class ToolController {

    private final ToolsService toolsService;
    private final PlannerExecutionService plannerExecutionService;

    public ToolController(ToolsService toolsService, PlannerExecutionService plannerExecutionService) {
        this.toolsService = toolsService;
        this.plannerExecutionService = plannerExecutionService;
    }

    @GetMapping("/calendar/items")
    public CalendarItemsResponse calendarItems() {
        return toolsService.listCalendarItems();
    }

    @PutMapping("/calendar/items/{id}")
    public CalendarItemRecord updateCalendarItem(@PathVariable("id") String id, @RequestBody @Valid CalendarItemUpdateRequest request) {
        return toolsService.updateCalendarItem(id, request);
    }

    @DeleteMapping("/calendar/items/{id}")
    public void deleteCalendarItem(@PathVariable("id") String id) {
        toolsService.deleteCalendarItem(id);
    }

    @GetMapping("/email/drafts")
    public EmailDraftsResponse emailDrafts() {
        return toolsService.listEmailDrafts();
    }

    @PutMapping("/email/drafts/{id}")
    public EmailDraftRecord updateEmailDraft(@PathVariable("id") String id, @RequestBody @Valid EmailDraftUpdateRequest request) {
        return toolsService.updateEmailDraft(id, request);
    }

    @PostMapping("/email/drafts/{id}/schedule")
    public EmailDraftRecord scheduleEmailDraft(@PathVariable("id") String id, @RequestBody @Valid EmailDraftScheduleRequest request) {
        return toolsService.scheduleEmailDraft(id, request);
    }

    @PostMapping("/email/drafts/{id}/send")
    public EmailDraftRecord sendEmailDraft(@PathVariable("id") String id) {
        return toolsService.sendEmailDraft(id);
    }

    @DeleteMapping("/email/drafts/{id}")
    public void deleteEmailDraft(@PathVariable("id") String id) {
        toolsService.deleteEmailDraft(id);
    }

    @GetMapping("/contacts")
    public ContactsResponse contacts() {
        return toolsService.listContacts();
    }

    @PostMapping("/contacts")
    public ContactRecord upsertContact(@RequestBody @Valid ContactUpsertRequest request) {
        return toolsService.upsertContact(request);
    }

    @DeleteMapping("/contacts/{id}")
    public void deleteContact(@PathVariable("id") String id) {
        toolsService.deleteContact(id);
    }

    @PostMapping("/rag/ingest")
    public RagIngestResponse ingest(@RequestBody @Valid RagIngestRequest request) {
        return toolsService.ingestRag(request);
    }

    @GetMapping("/state")
    public SystemStateResponse systemState() {
        return toolsService.systemState();
    }

    @PutMapping("/state/model")
    public SystemStateResponse selectGenerationModel(@RequestBody @Valid ModelSelectionRequest request) {
        return toolsService.selectGenerationModel(request);
    }

    @PostMapping("/planner/execute")
    public PlannerExecutionResult executePlannerAction(@RequestBody PlannerActionPlan request) {
        return plannerExecutionService.execute(request);
    }
}

