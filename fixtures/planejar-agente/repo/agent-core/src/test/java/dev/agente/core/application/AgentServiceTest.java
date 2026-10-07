package dev.agente.core.application;

import dev.agente.core.domain.AgentPolicy;
import dev.agente.core.domain.AgentRun;
import dev.agente.core.domain.Decision;
import dev.agente.core.domain.Goal;
import dev.agente.core.domain.InvalidGoalException;
import dev.agente.core.domain.RunStatus;
import dev.agente.core.engine.AgentContext;
import dev.agente.core.engine.AgentEngine;
import dev.agente.core.engine.ToolRegistry;
import dev.agente.core.port.Brain;
import dev.agente.core.port.IdGenerator;
import dev.agente.core.port.RunRepository;
import dev.agente.core.port.Tool;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentServiceTest {

    private final Map<String, AgentRun> store = new HashMap<>();
    private final RunRepository runs = new RunRepository() {
        @Override
        public void save(AgentRun run) {
            store.put(run.id(), run);
        }

        @Override
        public Optional<AgentRun> findById(String id) {
            return Optional.ofNullable(store.get(id));
        }
    };
    private final IdGenerator ids = () -> "generated-id";
    private final Brain brain = ctx -> new Decision.Finish("answer");
    private final AgentEngine engine = new AgentEngine(brain, new ToolRegistry(List.of()), AgentPolicy.defaults());
    private final AgentService service = new AgentService(engine, runs, ids);

    @Test
    void generatesIdStripsGoalSavesAndFinds() {
        AgentRun run = service.start("  spaced goal  ");

        assertThat(run.id()).isEqualTo("generated-id");
        assertThat(run.goal()).isEqualTo("spaced goal");
        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(store).containsKey("generated-id");
        assertThat(service.find("generated-id")).contains(run);
    }

    @Test
    void propagatesInvalidGoal() {
        assertThatThrownBy(() -> service.start("   "))
                .isInstanceOf(InvalidGoalException.class)
                .hasMessage("invalid_goal");
        assertThat(store).isEmpty();
    }

    @Test
    void findMissingIdReturnsEmpty() {
        assertThat(service.find("nope")).isEmpty();
    }

    @Test
    void usesToolThroughTheEngine() {
        Brain toolBrain = ctx -> new Decision.UseTool("echo", Map.of("x", "7"));
        Tool echo = new Tool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "echo";
            }

            @Override
            public String execute(Map<String, String> args) {
                return "echo:" + args.get("x");
            }
        };
        AgentEngine toolEngine = new AgentEngine(toolBrain, new ToolRegistry(List.of(echo)), new AgentPolicy(1, 1));
        AgentService toolService = new AgentService(toolEngine, runs, ids);

        AgentRun run = toolService.start("goal");

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.failureReason()).isEqualTo("max_steps_exceeded");
        assertThat(run.steps()).hasSize(1);
        assertThat(run.steps().get(0).output()).isEqualTo("echo:7");
    }

    @Test
    void contextCarriesGoalAndTools() {
        Brain capturingBrain = ctx -> {
            assertThat(ctx.goal()).isEqualTo(new Goal("g"));
            assertThat(ctx.tools()).hasSize(1);
            return new Decision.Finish("ok");
        };
        Tool echo = new Tool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "echo";
            }

            @Override
            public String execute(Map<String, String> args) {
                return "ok";
            }
        };
        AgentEngine capturingEngine = new AgentEngine(capturingBrain, new ToolRegistry(List.of(echo)), AgentPolicy.defaults());
        AgentService capturingService = new AgentService(capturingEngine, runs, ids);

        assertThat(capturingService.start("g").status()).isEqualTo(RunStatus.SUCCEEDED);
    }

    @Test
    void agentContextCopiesDefensively() {
        AgentContext ctx = new AgentContext(new Goal("g"), List.of(), List.of());
        assertThatThrownBy(() -> ctx.tools().add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ctx.history().add(null)).isInstanceOf(UnsupportedOperationException.class);
    }
}
