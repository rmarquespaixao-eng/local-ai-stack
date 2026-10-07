package dev.agente.core.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRunTest {

    private static final Goal GOAL = new Goal("do something");

    @Test
    void succeededCarriesAnswerAndNoFailureReason() {
        Step step = new Step(1, "math.add", Map.of("a", "1", "b", "2"), "3", true);
        AgentRun run = AgentRun.succeeded("id-1", GOAL, List.of(step), "done");

        assertThat(run.id()).isEqualTo("id-1");
        assertThat(run.goal()).isEqualTo("do something");
        assertThat(run.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.steps()).containsExactly(step);
        assertThat(run.answer()).isEqualTo("done");
        assertThat(run.failureReason()).isNull();
    }

    @Test
    void failedCarriesReasonAndNoAnswer() {
        AgentRun run = AgentRun.failed("id-2", GOAL, List.of(), "too_many_errors");

        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.steps()).isEmpty();
        assertThat(run.answer()).isNull();
        assertThat(run.failureReason()).isEqualTo("too_many_errors");
    }

    @Test
    void stepsAreImmutable() {
        AgentRun run = AgentRun.succeeded("id-3", GOAL, new ArrayList<>(), "answer");
        assertThatThrownBy(() -> run.steps().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
