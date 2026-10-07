package dev.agente.core.port;

import dev.agente.core.domain.Decision;
import dev.agente.core.engine.AgentContext;

public interface Brain {

    Decision decide(AgentContext context);
}
