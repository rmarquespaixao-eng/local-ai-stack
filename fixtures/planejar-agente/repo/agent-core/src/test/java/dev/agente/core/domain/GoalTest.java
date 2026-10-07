package dev.agente.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoalTest {

    @Test
    void stripsTheValue() {
        assertThat(new Goal("  hello  ").value()).isEqualTo("hello");
    }

    @Test
    void acceptsExactlyMaxLength() {
        String goal = "x".repeat(Goal.MAX_LENGTH);
        assertThat(new Goal(goal).value()).isEqualTo(goal);
    }

    @Test
    void rejectsMoreThanMaxLength() {
        assertThatThrownBy(() -> new Goal("x".repeat(Goal.MAX_LENGTH + 1)))
                .isInstanceOf(InvalidGoalException.class)
                .hasMessage("invalid_goal");
    }

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> new Goal(null))
                .isInstanceOf(InvalidGoalException.class)
                .hasMessage("invalid_goal");
    }

    @Test
    void rejectsBlank() {
        assertThatThrownBy(() -> new Goal("   "))
                .isInstanceOf(InvalidGoalException.class)
                .hasMessage("invalid_goal");
    }
}
