package dev.agente.core.engine;

import dev.agente.core.port.Tool;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ToolRegistry {

    private final List<Tool> tools;

    public ToolRegistry(List<? extends Tool> tools) {
        Set<String> names = new HashSet<>();
        List<Tool> copy = new ArrayList<>(tools);
        for (Tool tool : copy) {
            if (!names.add(tool.name())) {
                throw new IllegalArgumentException("duplicate_tool: " + tool.name());
            }
        }
        copy.sort(Comparator.comparing(Tool::name));
        this.tools = List.copyOf(copy);
    }

    public Optional<Tool> find(String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst();
    }

    public List<Tool> all() {
        return tools;
    }
}
