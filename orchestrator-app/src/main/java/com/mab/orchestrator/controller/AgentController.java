package com.mab.orchestrator.controller;

import com.mab.orchestrator.service.PlannerAgentService;
import com.mab.shared.model.AgentQueryRequest;
import com.mab.shared.model.AgentQueryResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/agent")
@CrossOrigin(origins = "*")
public class AgentController {

    private final PlannerAgentService planner;

    public AgentController(PlannerAgentService planner) {
        this.planner = planner;
    }

    @PostMapping("/query")
    public AgentQueryResponse query(@RequestBody @Valid AgentQueryRequest request) {
        return planner.execute(request);
    }
}
