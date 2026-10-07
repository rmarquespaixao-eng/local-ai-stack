package dev.agente.springai;

import dev.agente.core.domain.Goal;
import dev.agente.core.domain.Step;
import dev.agente.core.port.Tool;

import java.util.List;

public final class PromptFactory {

    private PromptFactory() {
    }

    public static String system(List<Tool> tools) {
        StringBuilder sb = new StringBuilder();
        sb.append("Responda APENAS com um objeto JSON, sem texto adicional.\n");
        sb.append("Formatos possíveis:\n");
        sb.append("{\"action\":\"tool\",\"tool\":\"<nome>\",\"args\":{\"<chave>\":\"<valor>\"}}\n");
        sb.append("{\"action\":\"finish\",\"answer\":\"<resposta final>\"}\n");
        sb.append("Ferramentas disponíveis:\n");
        for (Tool tool : tools) {
            sb.append("- ").append(tool.name()).append(": ").append(tool.description()).append('\n');
        }
        return sb.toString();
    }

    public static String user(Goal goal, List<Step> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("Objetivo: ").append(goal.value()).append('\n');
        sb.append("Histórico:\n");
        if (history.isEmpty()) {
            sb.append("(vazio)\n");
            return sb.toString();
        }
        for (Step step : history) {
            sb.append('#').append(step.index()).append(' ')
                    .append(step.tool()).append(' ')
                    .append(step.args()).append(" -> ")
                    .append(step.output()).append('\n');
        }
        return sb.toString();
    }
}
