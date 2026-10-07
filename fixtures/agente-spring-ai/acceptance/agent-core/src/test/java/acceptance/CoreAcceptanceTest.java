package acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agente.core.application.AgentService;
import dev.agente.core.domain.AgentPolicy;
import dev.agente.core.domain.AgentRun;
import dev.agente.core.domain.Decision;
import dev.agente.core.domain.Goal;
import dev.agente.core.domain.InvalidGoalException;
import dev.agente.core.domain.RunStatus;
import dev.agente.core.domain.Step;
import dev.agente.core.engine.AgentContext;
import dev.agente.core.engine.AgentEngine;
import dev.agente.core.engine.ToolRegistry;
import dev.agente.core.port.Brain;
import dev.agente.core.port.RunRepository;
import dev.agente.core.port.Tool;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CoreAcceptanceTest {
    record StubTool(String name, String description, java.util.function.Function<Map<String, String>, String> fn) implements Tool {
        public String execute(Map<String, String> args) { return fn.apply(args); }
    }

    static final class ScriptedBrain implements Brain {
        final Deque<Decision> script;
        final List<AgentContext> seen = new ArrayList<>();
        ScriptedBrain(Decision... decisions) { script = new ArrayDeque<>(List.of(decisions)); }
        public Decision decide(AgentContext ctx) {
            seen.add(ctx);
            if (script.isEmpty()) throw new IllegalStateException("script_exhausted");
            return script.poll();
        }
    }

    static final Tool ECHO = new StubTool("echo", "eco", a -> "echo:" + a.get("v"));
    static final Tool BOOM = new StubTool("boom", "falha", a -> { throw new IllegalStateException("kaput"); });

    static AgentRun run(Brain brain, AgentPolicy policy, Tool... tools) {
        return new AgentEngine(brain, new ToolRegistry(List.of(tools)), policy).run("r1", new Goal("objetivo"));
    }

    @Test void goalTrimsAndValidates() {
        assertThat(new Goal("  x  ").value()).isEqualTo("x");
        assertThat(new Goal("a".repeat(500)).value()).hasSize(500);
        for (String bad : new String[] {null, "", "   ", "a".repeat(501)})
            assertThatThrownBy(() -> new Goal(bad)).isInstanceOf(InvalidGoalException.class).isInstanceOf(IllegalArgumentException.class).hasMessage("invalid_goal");
    }

    @Test void policyDefaultsAndValidation() {
        assertThat(AgentPolicy.defaults()).isEqualTo(new AgentPolicy(8, 2));
        assertThatThrownBy(() -> new AgentPolicy(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentPolicy(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void registryRejectsDuplicatesAndSortsByName() {
        Tool z = new StubTool("z", "", a -> ""), a = new StubTool("a", "", x -> "");
        ToolRegistry reg = new ToolRegistry(List.of(z, a));
        assertThat(reg.all()).extracting(Tool::name).containsExactly("a", "z");
        assertThat(reg.find("a")).containsSame(a);
        assertThat(reg.find("nope")).isEmpty();
        assertThatThrownBy(() -> new ToolRegistry(List.of(a, a))).isInstanceOf(IllegalArgumentException.class).hasMessage("duplicate_tool: a");
    }

    @Test void finishImmediately() {
        AgentRun r = run(new ScriptedBrain(new Decision.Finish("pronto")), AgentPolicy.defaults(), ECHO);
        assertThat(r).isEqualTo(new AgentRun("r1", "objetivo", RunStatus.SUCCEEDED, List.of(), "pronto", null));
    }

    @Test void toolThenFinishRecordsStepsAndFeedsHistory() {
        ScriptedBrain brain = new ScriptedBrain(new Decision.UseTool("echo", Map.of("v", "1")), new Decision.Finish("ok"));
        AgentRun r = run(brain, AgentPolicy.defaults(), ECHO);
        assertThat(r.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(r.steps()).containsExactly(new Step(1, "echo", Map.of("v", "1"), "echo:1", true));
        assertThat(brain.seen).hasSize(2);
        assertThat(brain.seen.get(0).history()).isEmpty();
        assertThat(brain.seen.get(1).history()).isEqualTo(r.steps());
        assertThat(brain.seen.get(0).goal()).isEqualTo(new Goal("objetivo"));
        assertThat(brain.seen.get(0).tools()).extracting(Tool::name).containsExactly("echo");
    }

    @Test void errorStreakFailsWithTooManyErrors() {
        ScriptedBrain brain = new ScriptedBrain(new Decision.UseTool("ghost", Map.of()), new Decision.UseTool("boom", Map.of()));
        AgentRun r = run(brain, AgentPolicy.defaults(), ECHO, BOOM);
        assertThat(r.status()).isEqualTo(RunStatus.FAILED);
        assertThat(r.failureReason()).isEqualTo("too_many_errors");
        assertThat(r.answer()).isNull();
        assertThat(r.steps()).extracting(Step::output).containsExactly("unknown_tool: ghost", "tool_error: kaput");
        assertThat(r.steps()).extracting(Step::ok).containsExactly(false, false);
    }

    @Test void successResetsErrorStreak() {
        ScriptedBrain brain = new ScriptedBrain(
            new Decision.UseTool("boom", Map.of()), new Decision.UseTool("echo", Map.of("v", "x")),
            new Decision.UseTool("boom", Map.of()), new Decision.Finish("fim"));
        AgentRun r = run(brain, AgentPolicy.defaults(), ECHO, BOOM);
        assertThat(r.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(r.steps()).extracting(Step::index).containsExactly(1, 2, 3);
    }

    @Test void maxStepsExceeded() {
        ScriptedBrain brain = new ScriptedBrain(new Decision.UseTool("echo", Map.of()), new Decision.UseTool("echo", Map.of()), new Decision.Finish("tarde"));
        AgentRun r = run(brain, new AgentPolicy(2, 2), ECHO);
        assertThat(r.status()).isEqualTo(RunStatus.FAILED);
        assertThat(r.failureReason()).isEqualTo("max_steps_exceeded");
        assertThat(r.steps()).hasSize(2);
        assertThat(brain.seen).hasSize(2);
    }

    @Test void brainErrorsFailTheRun() {
        AgentRun thrown = run(ctx -> { throw new IllegalStateException("offline"); }, AgentPolicy.defaults(), ECHO);
        assertThat(thrown.failureReason()).isEqualTo("brain_error: offline");
        AgentRun nul = run(ctx -> null, AgentPolicy.defaults(), ECHO);
        assertThat(nul.failureReason()).isEqualTo("brain_error: null_decision");
        assertThat(nul.status()).isEqualTo(RunStatus.FAILED);
    }

    @Test void runIsImmutable() {
        AgentRun r = run(new ScriptedBrain(new Decision.UseTool("echo", new HashMap<>(Map.of("v", "1"))), new Decision.Finish("ok")), AgentPolicy.defaults(), ECHO);
        assertThatThrownBy(() -> r.steps().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> r.steps().get(0).args().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void serviceRunsSavesAndFinds() {
        Map<String, AgentRun> store = new HashMap<>();
        RunRepository repo = new RunRepository() {
            public void save(AgentRun run) { store.put(run.id(), run); }
            public Optional<AgentRun> findById(String id) { return Optional.ofNullable(store.get(id)); }
        };
        AgentEngine engine = new AgentEngine(new ScriptedBrain(new Decision.Finish("ok")), new ToolRegistry(List.of()), AgentPolicy.defaults());
        AgentService svc = new AgentService(engine, repo, () -> "id-7");
        AgentRun r = svc.start("  meta  ");
        assertThat(r.id()).isEqualTo("id-7");
        assertThat(r.goal()).isEqualTo("meta");
        assertThat(svc.find("id-7")).contains(r);
        assertThat(svc.find("x")).isEmpty();
        assertThatThrownBy(() -> svc.start(" ")).isInstanceOf(InvalidGoalException.class);
        assertThat(store).hasSize(1);
    }
}
