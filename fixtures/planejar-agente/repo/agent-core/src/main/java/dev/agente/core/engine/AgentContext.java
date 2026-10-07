package dev.agente.core.engine;

import dev.agente.core.domain.Goal;
import dev.agente.core.domain.Step;
import dev.agente.core.port.Tool;

import java.util.List;

public record AgentContext(Goal goal, List<Tool> tools, List<Step> history) {

    public AgentContext {
        tools = List.copyOf(tools);
        history = List.copyOf(history);
    }
}
