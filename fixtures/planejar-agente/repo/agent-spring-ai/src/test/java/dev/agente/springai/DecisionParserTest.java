package dev.agente.springai;

import dev.agente.core.domain.Decision;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DecisionParserTest {

    @Test
    void rejectsNull() {
        assertThatThrownBy(() -> DecisionParser.parse(null))
                .isInstanceOf(InvalidDecisionException.class)
                .hasMessage("invalid_decision");
    }

    @Test
    void removesThinkBlocksMultilineAndStrips() {
        Decision decision = DecisionParser.parse("  <think>\nreasoning here\n</think>\n{\"action\":\"finish\",\"answer\":\"hi\"}  ");

        assertThat(decision).isEqualTo(new Decision.Finish("hi"));
    }

    @Test
    void removesJsonFence() {
        Decision decision = DecisionParser.parse("```json\n{\"action\":\"finish\",\"answer\":\"hi\"}\n```");

        assertThat(decision).isEqualTo(new Decision.Finish("hi"));
    }

    @Test
    void rejectsNonObjectJsonAndInvalidJson() {
        assertThatThrownBy(() -> DecisionParser.parse("[1, 2]"))
                .isInstanceOf(InvalidDecisionException.class);
        assertThatThrownBy(() -> DecisionParser.parse("\"just a string\""))
                .isInstanceOf(InvalidDecisionException.class);
        assertThatThrownBy(() -> DecisionParser.parse("{not json"))
                .isInstanceOf(InvalidDecisionException.class);
    }

    @Test
    void parsesFinish() {
        Decision decision = DecisionParser.parse("{\"action\":\"finish\",\"answer\":\"the answer\"}");

        assertThat(decision).isEqualTo(new Decision.Finish("the answer"));
    }

    @Test
    void finishRequiresStringAnswer() {
        assertThatThrownBy(() -> DecisionParser.parse("{\"action\":\"finish\"}"))
                .isInstanceOf(InvalidDecisionException.class);
        assertThatThrownBy(() -> DecisionParser.parse("{\"action\":\"finish\",\"answer\":42}"))
                .isInstanceOf(InvalidDecisionException.class);
    }

    @Test
    void parsesToolWithArgs() {
        Decision decision = DecisionParser.parse("{\"action\":\"tool\",\"tool\":\"math.add\",\"args\":{\"a\":\"1\",\"b\":\"2\"}}");

        assertThat(decision).isEqualTo(new Decision.UseTool("math.add", Map.of("a", "1", "b", "2")));
    }

    @Test
    void argsAreOptional() {
        assertThat(DecisionParser.parse("{\"action\":\"tool\",\"tool\":\"clock.now\"}"))
                .isEqualTo(new Decision.UseTool("clock.now", Map.of()));
        assertThat(DecisionParser.parse("{\"action\":\"tool\",\"tool\":\"clock.now\",\"args\":null}"))
                .isEqualTo(new Decision.UseTool("clock.now", Map.of()));
    }

    @Test
    void nonStringArgsEnterAsTheirJsonText() {
        Decision decision = DecisionParser.parse("{\"action\":\"tool\",\"tool\":\"t\",\"args\":{\"n\":2,\"b\":true,\"s\":\"x\"}}");

        assertThat(decision).isEqualTo(new Decision.UseTool("t", Map.of("n", "2", "b", "true", "s", "x")));
    }

    @Test
    void argsMustBeAnObject() {
        assertThatThrownBy(() -> DecisionParser.parse("{\"action\":\"tool\",\"tool\":\"t\",\"args\":[1]}"))
                .isInstanceOf(InvalidDecisionException.class);
    }

    @Test
    void toolMustBeNonBlankString() {
        assertThatThrownBy(() -> DecisionParser.parse("{\"action\":\"tool\",\"tool\":\"  \"}"))
                .isInstanceOf(InvalidDecisionException.class);
        assertThatThrownBy(() -> DecisionParser.parse("{\"action\":\"tool\"}"))
                .isInstanceOf(InvalidDecisionException.class);
        assertThatThrownBy(() -> DecisionParser.parse("{\"action\":\"tool\",\"tool\":5}"))
                .isInstanceOf(InvalidDecisionException.class);
    }

    @Test
    void unknownActionIsInvalid() {
        assertThatThrownBy(() -> DecisionParser.parse("{\"action\":\"jump\"}"))
                .isInstanceOf(InvalidDecisionException.class);
        assertThatThrownBy(() -> DecisionParser.parse("{\"no_action\":true}"))
                .isInstanceOf(InvalidDecisionException.class);
    }
}
