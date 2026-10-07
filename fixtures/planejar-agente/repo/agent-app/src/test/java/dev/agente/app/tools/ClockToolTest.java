package dev.agente.app.tools;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClockToolTest {

    @Test
    void nameAndDescription() {
        ClockTool tool = new ClockTool(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        assertThat(tool.name()).isEqualTo("clock.now");
        assertThat(tool.description()).isNotEmpty();
    }

    @Test
    void returnsFixedInstantAsIso8601() {
        ClockTool tool = new ClockTool(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

        assertThat(tool.execute(Map.of())).isEqualTo("2026-01-01T00:00:00Z");
    }
}
