package acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agente.core.domain.Decision;
import dev.agente.core.domain.Goal;
import dev.agente.core.domain.Step;
import dev.agente.core.engine.AgentContext;
import dev.agente.core.port.Tool;
import dev.agente.springai.DecisionParser;
import dev.agente.springai.InvalidDecisionException;
import dev.agente.springai.PromptFactory;
import dev.agente.springai.SpringAiBrain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class SpringAiAcceptanceTest {
    record T(String name, String description) implements Tool {
        public String execute(Map<String, String> args) { return ""; }
    }

    static final class FakeModel implements ChatModel {
        final String reply;
        final List<Prompt> prompts = new ArrayList<>();
        FakeModel(String reply) { this.reply = reply; }
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
        }
    }

    @Test void parsesToolWithNonStringArgs() {
        Decision d = DecisionParser.parse("{\"action\":\"tool\",\"tool\":\"math.add\",\"args\":{\"a\":2,\"b\":\"3\"}}");
        assertThat(d).isEqualTo(new Decision.UseTool("math.add", Map.of("a", "2", "b", "3")));
    }

    @Test void toolWithoutArgsHasEmptyMap() {
        assertThat(DecisionParser.parse("{\"action\":\"tool\",\"tool\":\"clock.now\"}")).isEqualTo(new Decision.UseTool("clock.now", Map.of()));
    }

    @Test void parsesFinishInsideFencesAndAfterThink() {
        String raw = "<think>vou terminar\ncom calma</think>\n```json\n{\"action\":\"finish\",\"answer\":\"5\"}\n```";
        assertThat(DecisionParser.parse(raw)).isEqualTo(new Decision.Finish("5"));
    }

    @Test void rejectsInvalidDecisions() {
        for (String bad : List.of("oi", "[]", "{\"action\":\"dance\"}", "{\"action\":\"tool\"}", "{\"action\":\"tool\",\"tool\":\" \"}",
                "{\"action\":\"finish\"}", "{\"action\":\"finish\",\"answer\":1}", "{\"action\":\"tool\",\"tool\":\"x\",\"args\":[1]}"))
            assertThatThrownBy(() -> DecisionParser.parse(bad)).as(bad).isInstanceOf(InvalidDecisionException.class).hasMessage("invalid_decision");
        assertThatThrownBy(() -> DecisionParser.parse(null)).isInstanceOf(InvalidDecisionException.class);
    }

    @Test void promptsDescribeToolsGoalAndHistory() {
        String sys = PromptFactory.system(List.of(new T("math.add", "Soma dois inteiros")));
        assertThat(sys).contains("math.add", "Soma dois inteiros", "\"action\":\"tool\"", "\"action\":\"finish\"");
        String user = PromptFactory.user(new Goal("somar 2 e 3"), List.of(new Step(1, "math.add", Map.of("a", "2"), "5", true)));
        assertThat(user).contains("somar 2 e 3", "#1 math.add", "-> 5");
    }

    @Test void brainSendsSystemAndUserAndParsesReply() {
        FakeModel model = new FakeModel("{\"action\":\"tool\",\"tool\":\"math.add\",\"args\":{\"a\":\"2\",\"b\":\"3\"}}");
        AgentContext ctx = new AgentContext(new Goal("somar {2} e 3"), List.of(new T("math.add", "Soma")),
            List.of(new Step(1, "math.add", Map.of("a", "{x}"), "{\"ok\":1}", false)));
        Decision d = new SpringAiBrain(model).decide(ctx);
        assertThat(d).isEqualTo(new Decision.UseTool("math.add", Map.of("a", "2", "b", "3")));
        assertThat(model.prompts).hasSize(1);
        Prompt p = model.prompts.get(0);
        assertThat(p.getSystemMessage().getText()).contains("math.add");
        assertThat(p.getUserMessage().getText()).contains("somar {2} e 3", "#1 math.add");
    }

    @Test void brainPropagatesInvalidDecision() {
        AgentContext ctx = new AgentContext(new Goal("x"), List.of(), List.of());
        assertThatThrownBy(() -> new SpringAiBrain(new FakeModel("não sei")).decide(ctx)).isInstanceOf(InvalidDecisionException.class);
    }
}
