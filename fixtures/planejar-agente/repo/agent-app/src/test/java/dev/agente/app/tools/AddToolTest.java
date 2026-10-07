package dev.agente.app.tools;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AddToolTest {

    private final AddTool tool = new AddTool();

    @Test
    void nameAndDescription() {
        assertThat(tool.name()).isEqualTo("math.add");
        assertThat(tool.description()).isNotEmpty();
    }

    @Test
    void sumsIntegers() {
        assertThat(tool.execute(Map.of("a", "2", "b", "3"))).isEqualTo("5");
        assertThat(tool.execute(Map.of("a", " 10 ", "b", " -4 "))).isEqualTo("6");
    }

    @Test
    void rejectsMissingBlankOrNonNumericArgs() {
        assertThatThrownBy(() -> tool.execute(Map.of("a", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_args");
        assertThatThrownBy(() -> tool.execute(Map.of("a", "1", "b", "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_args");
        assertThatThrownBy(() -> tool.execute(Map.of("a", "x", "b", "1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid_args");
    }
}
