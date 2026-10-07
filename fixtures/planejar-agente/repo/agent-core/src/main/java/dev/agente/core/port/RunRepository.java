package dev.agente.core.port;

import dev.agente.core.domain.AgentRun;

import java.util.Optional;

public interface RunRepository {

    void save(AgentRun run);

    Optional<AgentRun> findById(String id);
}
