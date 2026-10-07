package dev.agente.springai;

public final class InvalidDecisionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidDecisionException() {
        super("invalid_decision");
    }
}
