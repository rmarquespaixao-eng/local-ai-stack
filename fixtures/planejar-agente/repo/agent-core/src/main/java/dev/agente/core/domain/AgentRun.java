package dev.agente.core.domain;

import java.util.List;

public record AgentRun(String id, String goal, RunStatus status, List<Step> steps, String answer, String failureReason) {

    public AgentRun {
        steps = List.copyOf(steps);
    }

    public static AgentRun succeeded(String id, Goal goal, List<Step> steps, String answer) {
        return new AgentRun(id, goal.value(), RunStatus.SUCCEEDED, steps, answer, null);
    }

    public static AgentRun failed(String id, Goal goal, List<Step> steps, String reason) {
        return new AgentRun(id, goal.value(), RunStatus.FAILED, steps, null, reason);
    }
}
