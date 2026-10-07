package dev.agente.springai;

import dev.agente.core.domain.Decision;
import dev.agente.core.domain.Goal;
import dev.agente.core.domain.Step;
import dev.agente.core.engine.AgentContext;
import dev.agente.core.port.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiBrainTest {

    /** Fake ChatModel: devolve uma fila de respostas e guarda os prompts recebidos. */
    private static final class FakeChatModel implements ChatModel {

        private final Deque<String> answers = new ArrayDeque<>();
        private final List<Prompt> prompts = new ArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answers.poll()))));
        }
    }

    private static Tool tool(String name, String description) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public String execute(Map<String, String> args) {
                return "ok";
            }
        };
    }

    @Test
    void promptContainsToolsGoalAndHistory() {
        FakeChatModel model = new FakeChatModel();
        model.answers.add("{\"action\":\"finish\",\"answer\":\"done\"}");
        SpringAiBrain brain = new SpringAiBrain(model);
        AgentContext ctx = new AgentContext(
                new Goal("uppercase the text"),
                List.of(tool("text.upper", "uppercases text"), tool("math.add", "sums two integers")),
                List.of(new Step(1, "text.upper", Map.of("text", "hi"), "HI", true)));

        Decision decision = brain.decide(ctx);

        assertThat(decision).isEqualTo(new Decision.Finish("done"));
        Prompt prompt = model.prompts.get(0);
        String system = prompt.getSystemMessage().getText();
        String user = prompt.getUserMessage().getText();

        assertThat(system).contains("{\"action\":\"tool\"").contains("{\"action\":\"finish\"");
        assertThat(system).contains("- text.upper: uppercases text").contains("- math.add: sums two integers");
        assertThat(user).contains("Objetivo: uppercase the text");
        assertThat(user).contains("#1 text.upper");
        assertThat(user).contains("-> HI");
    }

    @Test
    void bracesReachTheModelIntact() {
        FakeChatModel model = new FakeChatModel();
        model.answers.add("{\"action\":\"finish\",\"answer\":\"ok {b} {}\"}");
        SpringAiBrain brain = new SpringAiBrain(model);
        AgentContext ctx = new AgentContext(
                new Goal("keep {json} braces {value}"),
                List.of(),
                List.of(new Step(1, "text.upper", Map.of("text", "{a}"), "{out} {}", true)));

        brain.decide(ctx);

        String user = model.prompts.get(0).getUserMessage().getText();
        assertThat(user).contains("{json}").contains("{value}");
        assertThat(user).contains("{a}").contains("{out} {}");
    }

    @Test
    void invalidResponsePropagatesInvalidDecisionException() {
        FakeChatModel model = new FakeChatModel();
        model.answers.add("this is not json");
        SpringAiBrain brain = new SpringAiBrain(model);
        AgentContext ctx = new AgentContext(new Goal("g"), List.of(), List.of());

        assertThatThrownBy(() -> brain.decide(ctx))
                .isInstanceOf(InvalidDecisionException.class)
                .hasMessage("invalid_decision");
    }

    @Test
    void nullResponsePropagatesInvalidDecisionException() {
        ChatModel model = prompt -> new ChatResponse(List.of(new Generation(new AssistantMessage(null))));
        SpringAiBrain brain = new SpringAiBrain(model);

        assertThatThrownBy(() -> brain.decide(new AgentContext(new Goal("g"), List.of(), List.of())))
                .isInstanceOf(InvalidDecisionException.class)
                .hasMessage("invalid_decision");
    }
}
