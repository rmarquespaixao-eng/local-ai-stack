package dev.agente.app.tools;

import dev.agente.core.port.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AddTool implements Tool {

    @Override
    public String name() {
        return "math.add";
    }

    @Override
    public String description() {
        return "Sums two integers given as args 'a' and 'b'.";
    }

    @Override
    public String execute(Map<String, String> args) {
        long a = parse(args.get("a"));
        long b = parse(args.get("b"));
        return String.valueOf(a + b);
    }

    private static long parse(String raw) {
        if (raw == null || raw.strip().isEmpty()) {
            throw new IllegalArgumentException("invalid_args");
        }
        try {
            return Long.parseLong(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid_args");
        }
    }
}
