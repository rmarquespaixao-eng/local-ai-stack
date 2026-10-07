package dev.agente.springai;

import dev.agente.core.domain.Decision;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.Map;

public final class DecisionParser {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String THINK_OPEN = "<think>";
    private static final String THINK_CLOSE = "</think>";
    private static final String FENCE = "```";

    private DecisionParser() {
    }

    public static Decision parse(String raw) {
        if (raw == null) {
            throw new InvalidDecisionException();
        }

        String text = stripThinkBlocks(raw).strip();
        if (text.startsWith(FENCE)) {
            text = stripFence(text).strip();
        }

        JsonNode node;
        try {
            node = MAPPER.readTree(text);
        } catch (JacksonException e) {
            throw new InvalidDecisionException();
        }
        if (node == null || !node.isObject()) {
            throw new InvalidDecisionException();
        }

        String action = textOf(node, "action");
        if (action == null) {
            throw new InvalidDecisionException();
        }
        if ("finish".equals(action)) {
            return parseFinish(node);
        }
        if ("tool".equals(action)) {
            return parseTool(node);
        }
        throw new InvalidDecisionException();
    }

    private static String stripThinkBlocks(String raw) {
        StringBuilder sb = new StringBuilder(raw);
        int open;
        while ((open = sb.indexOf(THINK_OPEN)) >= 0) {
            int close = sb.indexOf(THINK_CLOSE, open);
            if (close < 0) {
                break;
            }
            sb.replace(open, close + THINK_CLOSE.length(), "");
        }
        return sb.toString();
    }

    private static String stripFence(String text) {
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0) {
            return "";
        }
        int lastFence = text.indexOf(FENCE, firstLineEnd);
        if (lastFence < 0) {
            return "";
        }
        return text.substring(firstLineEnd + 1, lastFence);
    }

    private static Decision parseFinish(JsonNode node) {
        JsonNode answer = node.get("answer");
        if (answer == null || !answer.isString()) {
            throw new InvalidDecisionException();
        }
        return new Decision.Finish(answer.asString());
    }

    private static Decision parseTool(JsonNode node) {
        String tool = textOf(node, "tool");
        if (tool == null || tool.isBlank()) {
            throw new InvalidDecisionException();
        }

        Map<String, String> args = new HashMap<>();
        JsonNode argsNode = node.get("args");
        if (argsNode == null || argsNode.isNull()) {
            return new Decision.UseTool(tool, args);
        }
        if (!argsNode.isObject()) {
            throw new InvalidDecisionException();
        }
        for (Map.Entry<String, JsonNode> entry : argsNode.properties()) {
            JsonNode value = entry.getValue();
            args.put(entry.getKey(), value.isString() ? value.asString() : value.toString());
        }
        return new Decision.UseTool(tool, args);
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }
}
