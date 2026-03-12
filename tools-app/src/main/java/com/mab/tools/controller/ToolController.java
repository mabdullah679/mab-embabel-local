package com.mab.tools.controller;

import com.mab.shared.model.CalendarItemsResponse;
import com.mab.shared.model.CalendarItemRecord;
import com.mab.shared.model.CalendarItemUpdateRequest;
import com.mab.shared.model.ContactRecord;
import com.mab.shared.model.ContactUpsertRequest;
import com.mab.shared.model.ContactsResponse;
import com.mab.shared.model.EmailDraftRecord;
import com.mab.shared.model.EmailDraftScheduleRequest;
import com.mab.shared.model.EmailDraftUpdateRequest;
import com.mab.shared.model.EmailDraftsResponse;
import com.mab.shared.model.ModelSelectionRequest;
import com.mab.shared.model.PlannerActionPlan;
import com.mab.shared.model.PlannerExecutionResult;
import com.mab.shared.model.RagIngestRequest;
import com.mab.shared.model.RagIngestResponse;
import com.mab.shared.model.SystemStateResponse;
import com.mab.tools.service.PlannerExecutionService;
import com.mab.tools.service.ToolsService;
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
