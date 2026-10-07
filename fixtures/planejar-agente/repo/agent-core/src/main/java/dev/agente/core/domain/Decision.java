package dev.agente.core.domain;

import java.util.Map;

public sealed interface Decision {

    record UseTool(String tool, Map<String, String> args) implements Decision {
        public UseTool {
            args = Map.copyOf(args);
        }
    }

    record Finish(String answer) implements Decision {
    }
}
