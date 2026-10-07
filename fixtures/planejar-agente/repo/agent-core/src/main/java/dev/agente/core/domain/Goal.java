package dev.agente.core.domain;

public record Goal(String value) {

    public static final int MAX_LENGTH = 500;

    public Goal {
        if (value == null) {
            throw new InvalidGoalException();
        }
        value = value.strip();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            throw new InvalidGoalException();
        }
    }
}
