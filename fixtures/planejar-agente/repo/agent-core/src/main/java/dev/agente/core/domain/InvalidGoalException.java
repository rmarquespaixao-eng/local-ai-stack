package dev.agente.core.domain;

public final class InvalidGoalException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public InvalidGoalException() {
        super("invalid_goal");
    }
}
