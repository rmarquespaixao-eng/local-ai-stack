package dev.agente.core.domain;

public record AgentPolicy(int maxSteps, int maxConsecutiveErrors) {

    public AgentPolicy {
        if (maxSteps < 1 || maxConsecutiveErrors < 1) {
            throw new IllegalArgumentException("invalid_policy");
        }
    }

    public static AgentPolicy defaults() {
        return new AgentPolicy(8, 2);
    }
}
