package dev.agente.app.web;

import dev.agente.core.application.AgentService;
import dev.agente.core.domain.AgentRun;
import dev.agente.core.domain.InvalidGoalException;
import dev.agente.core.domain.Step;
import dev.agente.core.engine.ToolRegistry;
import dev.agente.core.port.Tool;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class RunController {

    private final AgentService service;
    private final ToolRegistry tools;
    public RunController(AgentService service, ToolRegistry tools) {
        this.service = service;
        this.tools = tools;
    }

    @PostMapping("/runs")
    public ResponseEntity<Map<String, Object>> create(@RequestBody JsonNode body) {
        if (body == null || !body.isObject()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_json");
        }

        JsonNode goal = body.get("goal");
        if (goal == null || !goal.isString()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_goal");
        }

        try {
            AgentRun run = service.start(goal.asString());
            return ResponseEntity.status(HttpStatus.CREATED)
                    .header("Location", "/runs/" + run.id())
                    .body(toJson(run));
        } catch (InvalidGoalException e) {
            return error(HttpStatus.BAD_REQUEST, "invalid_goal");
        }
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return service.find(id)
                .map(run -> ResponseEntity.ok(toJson(run)))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "not_found"));
    }

    @GetMapping("/tools")
    public ResponseEntity<Object> listTools() {
        return ResponseEntity.ok(tools.all().stream()
                .map(tool -> Map.of("name", tool.name(), "description", tool.description()))
                .toList());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable() {
        return error(HttpStatus.BAD_REQUEST, "invalid_json");
    }

    private static Map<String, Object> toJson(AgentRun run) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", run.id());
        json.put("goal", run.goal());
        json.put("status", run.status().name());
        json.put("steps", run.steps().stream().map(RunController::stepJson).toList());
        json.put("answer", run.answer());
        json.put("failureReason", run.failureReason());
        return json;
    }

    private static Map<String, Object> stepJson(Step step) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("index", step.index());
        json.put("tool", step.tool());
        json.put("args", step.args());
        json.put("output", step.output());
        json.put("ok", step.ok());
        return json;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
