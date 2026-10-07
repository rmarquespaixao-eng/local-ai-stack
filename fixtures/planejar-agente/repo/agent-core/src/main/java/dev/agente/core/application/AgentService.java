package dev.agente.core.application;

import dev.agente.core.domain.AgentRun;
import dev.agente.core.domain.Goal;
import dev.agente.core.engine.AgentEngine;
import dev.agente.core.port.IdGenerator;
import dev.agente.core.port.RunRepository;

import java.util.Optional;

public final class AgentService {

    private final AgentEngine engine;
    private final RunRepository runs;
    private final IdGenerator ids;

    public AgentService(AgentEngine engine, RunRepository runs, IdGenerator ids) {
        this.engine = engine;
        this.runs = runs;
        this.ids = ids;
    }

    public AgentRun start(String rawGoal) {
        Goal goal = new Goal(rawGoal);
        AgentRun run = engine.run(ids.next(), goal);
        runs.save(run);
        return run;
    }

    public Optional<AgentRun> find(String id) {
        return runs.findById(id);
    }
}
