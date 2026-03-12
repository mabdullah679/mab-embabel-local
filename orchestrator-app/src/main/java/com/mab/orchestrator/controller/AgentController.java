package com.mab.orchestrator.controller;

import com.mab.orchestrator.service.PlannerAgentService;
import com.mab.shared.model.AgentQueryRequest;
import com.mab.shared.model.AgentQueryResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/agent")
@CrossOrigin(origins = "*")
public class AgentController {
    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final PlannerAgentService planner;

    public AgentController(PlannerAgentService planner) {
        this.planner = planner;
    }

    @PostMapping("/query")
    public AgentQueryResponse query(@RequestBody @Valid AgentQueryRequest request) {
        long start = System.currentTimeMillis();
        int historySize = request.history() == null ? 0 : request.history().size();
        log.info("Inbound /agent/query request. query='{}' historySize={}", request.query(), historySize);
        try {
            AgentQueryResponse response = planner.execute(request);
            log.info("Completed /agent/query in {} ms with {} traces", System.currentTimeMillis() - start, response.traces().size());
            return response;
        } catch (RuntimeException exception) {
            log.error("Failed /agent/query after {} ms", System.currentTimeMillis() - start, exception);
            throw exception;
        }
    }
}
