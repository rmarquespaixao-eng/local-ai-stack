package dev.agente.core.engine;

import dev.agente.core.domain.Decision;
import dev.agente.core.domain.Goal;
import dev.agente.core.port.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "desc " + name;
            }

            @Override
            public String execute(Map<String, String> args) {
                return "ok";
            }
        };
    }

    @Test
    void ordersToolsByName() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("zeta"), tool("alpha"), tool("mid")));

        assertThat(registry.all()).map(Tool::name).containsExactly("alpha", "mid", "zeta");
        assertThatThrownBy(() -> registry.all().add(tool("other")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void findsToolByName() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("alpha")));

        assertThat(registry.find("alpha")).map(Tool::name).contains("alpha");
        assertThat(registry.find("missing")).isEmpty();
    }

    @Test
    void rejectsDuplicateNames() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(tool("alpha"), tool("alpha"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("duplicate_tool: alpha");
    }
}
