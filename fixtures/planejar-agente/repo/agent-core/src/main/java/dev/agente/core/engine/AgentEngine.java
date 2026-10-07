package dev.agente.core.engine;

import dev.agente.core.domain.AgentPolicy;
import dev.agente.core.domain.AgentRun;
import dev.agente.core.domain.Decision;
import dev.agente.core.domain.Goal;
import dev.agente.core.domain.Step;
import dev.agente.core.port.Brain;
import dev.agente.core.port.Tool;

import java.util.ArrayList;
import java.util.List;

public final class AgentEngine {

    private final Brain brain;
    private final ToolRegistry tools;
    private final AgentPolicy policy;

    public AgentEngine(Brain brain, ToolRegistry tools, AgentPolicy policy) {
        this.brain = brain;
        this.tools = tools;
        this.policy = policy;
    }

    public AgentRun run(String runId, Goal goal) {
        List<Step> history = new ArrayList<>();
        int errors = 0;

        while (history.size() < policy.maxSteps()) {
            Decision decision;
            try {
                decision = brain.decide(new AgentContext(goal, tools.all(), history));
            } catch (RuntimeException e) {
                return AgentRun.failed(runId, goal, history, "brain_error: " + e.getMessage());
            }
            if (decision == null) {
                return AgentRun.failed(runId, goal, history, "brain_error: null_decision");
            }

            Step step;
            if (decision instanceof Decision.Finish finish) {
                return AgentRun.succeeded(runId, goal, history, finish.answer());
            }

            Decision.UseTool useTool = (Decision.UseTool) decision;
            step = execute(useTool, history.size() + 1);
            history.add(step);
            errors = step.ok() ? 0 : errors + 1;
            if (errors >= policy.maxConsecutiveErrors()) {
                return AgentRun.failed(runId, goal, history, "too_many_errors");
            }
        }

        return AgentRun.failed(runId, goal, history, "max_steps_exceeded");
    }

    private Step execute(Decision.UseTool useTool, int index) {
        Tool tool = tools.find(useTool.tool()).orElse(null);
        if (tool == null) {
            return new Step(index, useTool.tool(), useTool.args(), "unknown_tool: " + useTool.tool(), false);
        }
        try {
            return new Step(index, useTool.tool(), useTool.args(), tool.execute(useTool.args()), true);
        } catch (RuntimeException e) {
            return new Step(index, useTool.tool(), useTool.args(), "tool_error: " + e.getMessage(), false);
        }
    }
}
