package dev.agente.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentPolicyTest {

    @Test
    void defaults() {
        AgentPolicy policy = AgentPolicy.defaults();
        assertThat(policy.maxSteps()).isEqualTo(8);
        assertThat(policy.maxConsecutiveErrors()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> new AgentPolicy(0, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_policy");
        assertThatThrownBy(() -> new AgentPolicy(8, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_policy");
        assertThatThrownBy(() -> new AgentPolicy(-1, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_policy");
    }

    @Test
    void acceptsBoundaryValues() {
        AgentPolicy policy = new AgentPolicy(1, 1);
        assertThat(policy.maxSteps()).isEqualTo(1);
        assertThat(policy.maxConsecutiveErrors()).isEqualTo(1);
    }
}
