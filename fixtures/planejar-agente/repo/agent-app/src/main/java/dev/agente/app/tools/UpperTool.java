package dev.agente.app.tools;

import dev.agente.core.port.Tool;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

@Component
public class UpperTool implements Tool {

    @Override
    public String name() {
        return "text.upper";
    }

    @Override
    public String description() {
        return "Uppercases the required arg 'text'.";
    }

    @Override
    public String execute(Map<String, String> args) {
        String text = args.get("text");
        if (text == null || text.strip().isEmpty()) {
            throw new IllegalArgumentException("invalid_args");
        }
        return text.toUpperCase(Locale.ROOT);
    }
}
