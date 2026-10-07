package dev.agente.core.engine;

import dev.agente.core.domain.AgentPolicy;
import dev.agente.core.domain.AgentRun;
import dev.agente.core.domain.Decision;
import dev.agente.core.domain.Goal;
import dev.agente.core.domain.RunStatus;
import dev.agente.core.domain.Step;
import dev.agente.core.port.Brain;
import dev.agente.core.port.Tool;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEngineTest {

    private static final Goal GOAL = new Goal("solve it");

    private static Tool echoTool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
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
    }

    private static Tool failingTool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "boom";
            }

            @Override
            public String execute(Map<String, String> args) {
                throw new IllegalStateException("boom");
            }
        };
    }

    /** Brain roteirizado: consome uma fila de decisões; null lança, null_decision registra. */
    private static Brain scriptedBrain(Deque<Decision> script) {
        return ctx -> {
            if (script.isEmpty()) {
                throw new IllegalStateException("script_exhausted");
            }
            Decision decision = script.poll();
            if (decision == null) {
                throw new IllegalStateException("null_decision");
            }
            return decision;
        };
    }

    private static AgentEngine engine(Brain brain, AgentPolicy policy, List<Tool> tools) {
        return new AgentEngine(brain, new ToolRegistry(tools), policy);
    }

    @Test
    void finishImmediately() {
        Deque<Decision> script = new ArrayDeque<>(List.of(new Decision.Finish("answer")));
        AgentRun run = engine(scriptedBrain(script), AgentPolicy.defaults(), List.of()).run("r1", GOAL);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.answer()).isEqualTo("answer");
        assertThat(run.steps()).isEmpty();
    }

    @Test
    void toolThenFinishRecordsStepAndPassesHistoryToBrain() {
        Deque<Decision> script = new ArrayDeque<>(List.of(
                new Decision.UseTool("echo", Map.of("x", "1")),
                new Decision.Finish("done")));
        List<AgentContext> seen = new ArrayList<>();
        Brain brain = ctx -> {
            seen.add(ctx);
            return script.poll();
        };

        AgentRun run = engine(brain, AgentPolicy.defaults(), List.of(echoTool("echo"))).run("r2", GOAL);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.answer()).isEqualTo("done");
        assertThat(run.steps()).hasSize(1);
        Step step = run.steps().get(0);
        assertThat(step.index()).isEqualTo(1);
        assertThat(step.tool()).isEqualTo("echo");
        assertThat(step.args()).isEqualTo(Map.of("x", "1"));
        assertThat(step.output()).isEqualTo("echo:1");
        assertThat(step.ok()).isTrue();

        assertThat(seen).hasSize(2);
        assertThat(seen.get(1).history()).containsExactly(step);
        assertThat(seen.get(1).goal()).isEqualTo(GOAL);
    }

    @Test
    void unknownTool() {
        Deque<Decision> script = new ArrayDeque<>(List.of(
                new Decision.UseTool("missing", Map.of()),
                new Decision.Finish("done")));
        AgentRun run = engine(scriptedBrain(script), AgentPolicy.defaults(), List.of(echoTool("echo"))).run("r3", GOAL);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.steps()).hasSize(1);
        assertThat(run.steps().get(0).output()).isEqualTo("unknown_tool: missing");
        assertThat(run.steps().get(0).ok()).isFalse();
    }

    @Test
    void toolError() {
        Deque<Decision> script = new ArrayDeque<>(List.of(
                new Decision.UseTool("boom", Map.of()),
                new Decision.Finish("done")));
        AgentRun run = engine(scriptedBrain(script), new AgentPolicy(8, 3), List.of(failingTool("boom"))).run("r4", GOAL);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.steps().get(0).output()).isEqualTo("tool_error: boom");
        assertThat(run.steps().get(0).ok()).isFalse();
    }

    @Test
    void tooManyErrors() {
        Deque<Decision> script = new ArrayDeque<>(List.of(
                new Decision.UseTool("boom", Map.of()),
                new Decision.UseTool("boom", Map.of())));
        AgentRun run = engine(scriptedBrain(script), AgentPolicy.defaults(), List.of(failingTool("boom"))).run("r5", GOAL);

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.failureReason()).isEqualTo("too_many_errors");
        assertThat(run.steps()).hasSize(2);
    }

    @Test
    void successResetsTheErrorStreak() {
        Deque<Decision> script = new ArrayDeque<>(List.of(
                new Decision.UseTool("boom", Map.of()),
                new Decision.UseTool("echo", Map.of("x", "1")),
                new Decision.UseTool("boom", Map.of()),
                new Decision.Finish("done")));
        AgentRun run = engine(scriptedBrain(script), AgentPolicy.defaults(),
                List.of(failingTool("boom"), echoTool("echo"))).run("r6", GOAL);

        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.steps()).hasSize(3);
    }

    @Test
    void maxStepsExceeded() {
        Deque<Decision> script = new ArrayDeque<>();
        for (int i = 0; i < 3; i++) {
            script.add(new Decision.UseTool("echo", Map.of("x", String.valueOf(i))));
        }
        AgentRun run = engine(scriptedBrain(script), new AgentPolicy(2, 5), List.of(echoTool("echo"))).run("r7", GOAL);

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.failureReason()).isEqualTo("max_steps_exceeded");
        assertThat(run.steps()).hasSize(2);
    }

    @Test
    void brainError() {
        Brain brain = ctx -> {
            throw new IllegalStateException("llm_down");
        };
        AgentRun run = engine(brain, AgentPolicy.defaults(), List.of()).run("r8", GOAL);

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.failureReason()).isEqualTo("brain_error: llm_down");
    }

    @Test
    void brainNullDecision() {
        Brain brain = ctx -> null;
        AgentRun run = engine(brain, AgentPolicy.defaults(), List.of()).run("r9", GOAL);

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.failureReason()).isEqualTo("brain_error: null_decision");
    }
}
