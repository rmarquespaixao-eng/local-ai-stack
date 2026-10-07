package acceptance;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.agente.app.AgentApplication;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(classes = {AgentApplication.class, AppAcceptanceTest.Fakes.class})
@AutoConfigureMockMvc
class AppAcceptanceTest {
    static final Deque<String> REPLIES = new ArrayDeque<>();

    @TestConfiguration
    static class Fakes {
        @Bean @Primary ChatModel scriptedChatModel() {
            return new ChatModel() {
                public ChatResponse call(Prompt prompt) {
                    String r = REPLIES.isEmpty() ? "{\"action\":\"finish\",\"answer\":\"vazio\"}" : REPLIES.poll();
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(r))));
                }
            };
        }
    }

    @Autowired MockMvc mvc;

    @BeforeEach void reset() { REPLIES.clear(); }

    private MvcResult start(String json) throws Exception {
        return mvc.perform(post("/runs").contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    @Test void runUsesRealToolsAndIsRetrievable() throws Exception {
        REPLIES.add("{\"action\":\"tool\",\"tool\":\"math.add\",\"args\":{\"a\":2,\"b\":3}}");
        REPLIES.add("{\"action\":\"tool\",\"tool\":\"text.upper\",\"args\":{\"text\":\"cinco\"}}");
        REPLIES.add("```json\n{\"action\":\"finish\",\"answer\":\"5 / CINCO\"}\n```");
        MvcResult res = mvc.perform(post("/runs").contentType(MediaType.APPLICATION_JSON).content("{\"goal\":\"  somar e gritar  \"}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", matchesPattern("/runs/[0-9a-f-]{36}")))
            .andExpect(jsonPath("$.goal").value("somar e gritar"))
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.answer").value("5 / CINCO"))
            .andExpect(jsonPath("$.failureReason").value(nullValue()))
            .andExpect(jsonPath("$.steps[0].index").value(1))
            .andExpect(jsonPath("$.steps[0].tool").value("math.add"))
            .andExpect(jsonPath("$.steps[0].args.a").value("2"))
            .andExpect(jsonPath("$.steps[0].output").value("5"))
            .andExpect(jsonPath("$.steps[0].ok").value(true))
            .andExpect(jsonPath("$.steps[1].output").value("CINCO"))
            .andReturn();
        String location = res.getResponse().getHeader("Location");
        mvc.perform(get(location)).andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("5 / CINCO"));
    }

    @Test void badToolArgsAndInvalidDecisionFailGracefully() throws Exception {
        REPLIES.add("{\"action\":\"tool\",\"tool\":\"math.add\",\"args\":{\"a\":\"x\",\"b\":1}}");
        REPLIES.add("{\"action\":\"tool\",\"tool\":\"math.add\",\"args\":{}}");
        mvc.perform(post("/runs").contentType(MediaType.APPLICATION_JSON).content("{\"goal\":\"g\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.failureReason").value("too_many_errors"))
            .andExpect(jsonPath("$.steps[0].output").value("tool_error: invalid_args"))
            .andExpect(jsonPath("$.answer").value(nullValue()));
        REPLIES.add("blá");
        mvc.perform(post("/runs").contentType(MediaType.APPLICATION_JSON).content("{\"goal\":\"g\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.failureReason").value("brain_error: invalid_decision"));
    }

    @Test void validationAndNotFound() throws Exception {
        mvc.perform(post("/runs").contentType(MediaType.APPLICATION_JSON).content("{\"goal\":\"   \"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_goal"));
        mvc.perform(post("/runs").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_goal"));
        mvc.perform(post("/runs").contentType(MediaType.APPLICATION_JSON).content("{nope"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_json"));
        mvc.perform(get("/runs/nao-existe"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
    }
    // listsToolsSortedByName (3 ferramentas) foi substituído por FeatureV2AcceptanceTest.toolsListIncludesNotes (5).
}
