package dev.agente.app.tools;

import dev.agente.core.port.Tool;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;

@Component
public class ClockTool implements Tool {

    private final Clock clock;

    public ClockTool(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String name() {
        return "clock.now";
    }

    @Override
    public String description() {
        return "Returns the current instant as ISO-8601 text. Takes no args.";
    }

    @Override
    public String execute(Map<String, String> args) {
        return clock.instant().toString();
    }
}
