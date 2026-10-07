package dev.agente.app.web;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.hamcrest.Matchers.nullValue;

@SpringBootTest
@AutoConfigureMockMvc
class RunControllerTest {

    /** Fila de respostas roteirizadas para o ChatModel fake. */
    private static final Deque<String> answers = new ArrayDeque<>();

    @TestConfiguration
    static class FakeModelConfig {

        @Bean
        @Primary
        ChatModel fakeChatModel() {
            return (Prompt prompt) -> new ChatResponse(
                    List.of(new Generation(new AssistantMessage(answers.poll()))));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void postRunsExecutesAndReturns201WithLocation() throws Exception {
        answers.add("{\"action\":\"tool\",\"tool\":\"math.add\",\"args\":{\"a\":\"2\",\"b\":\"3\"}}");
        answers.add("{\"action\":\"finish\",\"answer\":\"the sum is 5\"}");

        String body = mockMvc.perform(post("/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"sum 2 and 3\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.goal").value("sum 2 and 3"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.steps[0].index").value(1))
                .andExpect(jsonPath("$.steps[0].tool").value("math.add"))
                .andExpect(jsonPath("$.steps[0].args.a").value("2"))
                .andExpect(jsonPath("$.steps[0].output").value("5"))
                .andExpect(jsonPath("$.steps[0].ok").value(true))
                .andExpect(jsonPath("$.answer").value("the sum is 5"))
                .andExpect(jsonPath("$.failureReason").value(nullValue()))
                .andReturn().getResponse().getContentAsString();

        String id = com.jayway.jsonpath.JsonPath.read(body, "$.id");
        mockMvc.perform(get("/runs/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void postRunsRejectsBlankGoal() throws Exception {
        mockMvc.perform(post("/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_goal"));
    }

    @Test
    void postRunsRejectsMissingGoal() throws Exception {
        mockMvc.perform(post("/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"something\":\"else\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_goal"));
    }

    @Test
    void postRunsRejectsGoalOverMaxLength() throws Exception {
        mockMvc.perform(post("/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_goal"));
    }

    @Test
    void postRunsRejectsInvalidJsonBody() throws Exception {
        mockMvc.perform(post("/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_json"));
    }

    @Test
    void getRunsReturns404WhenMissing() throws Exception {
        mockMvc.perform(get("/runs/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void getToolsReturnsSortedTools() throws Exception {
        mockMvc.perform(get("/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("clock.now"))
                .andExpect(jsonPath("$[0].description").isNotEmpty())
                .andExpect(jsonPath("$[1].name").value("math.add"))
                .andExpect(jsonPath("$[2].name").value("text.upper"))
                .andExpect(jsonPath("$[2].description").isNotEmpty());
    }

    @Test
    void failedRunIsReturnedWithFailureReason() throws Exception {
        answers.add("{\"action\":\"tool\",\"tool\":\"math.add\",\"args\":{\"a\":\"x\",\"b\":\"1\"}}");
        answers.add("{\"action\":\"tool\",\"tool\":\"math.add\",\"args\":{\"a\":\"y\",\"b\":\"1\"}}");

        mockMvc.perform(post("/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goal\":\"bad args\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("too_many_errors"))
                .andExpect(jsonPath("$.answer").value(nullValue()))
                .andExpect(jsonPath("$.steps[0].output").value("tool_error: invalid_args"));
    }
}
