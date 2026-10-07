package dev.agente.app.tools;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpperToolTest {

    private final UpperTool tool = new UpperTool();

    @Test
    void nameAndDescription() {
        assertThat(tool.name()).isEqualTo("text.upper");
        assertThat(tool.description()).isNotEmpty();
    }

    @Test
    void uppercasesText() {
        assertThat(tool.execute(Map.of("text", "hello"))).isEqualTo("HELLO");
    }

    @Test
    void rejectsMissingOrBlankText() {
        assertThatThrownBy(() -> tool.execute(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_args");
        assertThatThrownBy(() -> tool.execute(Map.of("text", "   ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_args");
    }
}
