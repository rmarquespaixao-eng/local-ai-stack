package dev.agente.core.domain;

import java.util.Map;

public record Step(int index, String tool, Map<String, String> args, String output, boolean ok) {

    public Step {
        args = Map.copyOf(args);
    }
}
