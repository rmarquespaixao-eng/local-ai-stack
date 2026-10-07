package dev.agente.core.port;

import java.util.Map;

public interface Tool {

    String name();

    String description();

    String execute(Map<String, String> args);
}
