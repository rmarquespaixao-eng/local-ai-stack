package dev.agente.app.config;

import dev.agente.core.domain.AgentRun;
import dev.agente.core.port.RunRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryRunRepository implements RunRepository {

    private final Map<String, AgentRun> runs = new ConcurrentHashMap<>();

    @Override
    public void save(AgentRun run) {
        runs.put(run.id(), run);
    }

    @Override
    public Optional<AgentRun> findById(String id) {
        return Optional.ofNullable(runs.get(id));
    }
}
