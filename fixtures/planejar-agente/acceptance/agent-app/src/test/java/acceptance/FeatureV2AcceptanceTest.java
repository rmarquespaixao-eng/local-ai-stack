package acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.agente.app.AgentApplication;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.ResultActions;

// Aceite oculto da fixture planejar-agente (FEATURE.md v2): só HTTP + ChatModel roteirizado,
// independente do desenho interno escolhido pelo planejador.
@SpringBootTest(classes = {AgentApplication.class, FeatureV2AcceptanceTest.Fakes.class})
@AutoConfigureMockMvc
class FeatureV2AcceptanceTest {
    static final Deque<String> REPLIES = new ArrayDeque<>();
    static final List<String> USER_PROMPTS = new ArrayList<>();

    @TestConfiguration
    static class Fakes {
        @Bean @Primary ChatModel scriptedChatModel() {
            return new ChatModel() {
                public ChatResponse call(Prompt prompt) {
                    USER_PROMPTS.add(prompt.getUserMessage().getText());
                    String r = REPLIES.isEmpty() ? "{\"action\":\"finish\",\"answer\":\"vazio\"}" : REPLIES.poll();
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(r))));
                }
            };
        }
    }

    @Autowired MockMvc mvc;

    @BeforeEach void reset() { REPLIES.clear(); USER_PROMPTS.clear(); }

    private ResultActions start(String json) throws Exception {
        return mvc.perform(post("/runs").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String startId(String json) throws Exception {
        String body = start(json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private static String tool(String name, String argsJson) {
        return "{\"action\":\"tool\",\"tool\":\"" + name + "\",\"args\":" + argsJson + "}";
    }

    @Test void notesAreWrittenReadAndReturnedWithTheRun() throws Exception {
        REPLIES.add(tool("notes.write", "{\"key\":\"plano\",\"value\":\"somar\"}"));
        REPLIES.add(tool("notes.write", "{\"key\":\"a\",\"value\":\"2\"}"));
        REPLIES.add(tool("notes.read", "{\"key\":\"plano\"}"));
        REPLIES.add(tool("notes.write", "{\"key\":\"plano\",\"value\":\"\"}"));
        REPLIES.add("{\"action\":\"finish\",\"answer\":\"feito\"}");
        String body = start("{\"goal\":\"usar notas\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("SUCCEEDED"))
            .andExpect(jsonPath("$.steps[0].output").value("ok"))
            .andExpect(jsonPath("$.steps[0].ok").value(true))
            .andExpect(jsonPath("$.steps[2].output").value("somar"))
            .andExpect(jsonPath("$.notes.plano").value(""))
            .andExpect(jsonPath("$.notes.a").value("2"))
            .andExpect(jsonPath("$.notes.length()").value(2))
            .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        mvc.perform(get("/runs/" + id)).andExpect(status().isOk())
            .andExpect(jsonPath("$.notes.a").value("2")).andExpect(jsonPath("$.notes.plano").value(""));
    }

    @Test void modelSeesNotesEveryTurnInKeyOrder() throws Exception {
        REPLIES.add(tool("notes.write", "{\"key\":\"zeta\",\"value\":\"9\"}"));
        REPLIES.add(tool("notes.write", "{\"key\":\"alfa\",\"value\":\"1\"}"));
        REPLIES.add("{\"action\":\"finish\",\"answer\":\"ok\"}");
        start("{\"goal\":\"ver notas\"}").andExpect(status().isCreated());
        assertThat(USER_PROMPTS).hasSize(3);
        assertThat(USER_PROMPTS.get(0)).contains("Notas: (vazio)");
        assertThat(USER_PROMPTS.get(1)).contains("Notas:").contains("- zeta = 9").doesNotContain("Notas: (vazio)");
        String last = USER_PROMPTS.get(2);
        assertThat(last).contains("- alfa = 1").contains("- zeta = 9");
        assertThat(last.indexOf("- alfa = 1")).isLessThan(last.indexOf("- zeta = 9"));
    }

    @Test void notesErrorsAreFailedSteps() throws Exception {
        REPLIES.add(tool("notes.read", "{\"key\":\"nada\"}"));
        REPLIES.add(tool("notes.write", "{\"key\":\"  \",\"value\":\"x\"}"));
        start("{\"goal\":\"erros\"}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.failureReason").value("too_many_errors"))
            .andExpect(jsonPath("$.steps[0].output").value("tool_error: note_not_found"))
            .andExpect(jsonPath("$.steps[0].ok").value(false))
            .andExpect(jsonPath("$.steps[1].output").value("tool_error: invalid_args"))
            .andExpect(jsonPath("$.notes.length()").value(0));
        REPLIES.add(tool("notes.write", "{\"key\":\"k\"}"));
        REPLIES.add("{\"action\":\"finish\",\"answer\":\"x\"}");
        start("{\"goal\":\"sem value\"}")
            .andExpect(jsonPath("$.steps[0].output").value("tool_error: invalid_args"))
            .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test void notesAreIsolatedPerRun() throws Exception {
        REPLIES.add(tool("notes.write", "{\"key\":\"segredo\",\"value\":\"42\"}"));
        REPLIES.add("{\"action\":\"finish\",\"answer\":\"a\"}");
        start("{\"goal\":\"run A\"}").andExpect(jsonPath("$.notes.segredo").value("42"));
        REPLIES.add(tool("notes.read", "{\"key\":\"segredo\"}"));
        REPLIES.add("{\"action\":\"finish\",\"answer\":\"b\"}");
        USER_PROMPTS.clear();
        start("{\"goal\":\"run B\"}")
            .andExpect(jsonPath("$.steps[0].output").value("tool_error: note_not_found"))
            .andExpect(jsonPath("$.notes.length()").value(0));
        assertThat(USER_PROMPTS.get(0)).contains("Notas: (vazio)");
    }

    @Test void runWithoutNotesHasEmptyNotesObject() throws Exception {
        start("{\"goal\":\"nada\"}")
            .andExpect(jsonPath("$.notes").isMap())
            .andExpect(jsonPath("$.notes.length()").value(0))
            .andExpect(jsonPath("$.answer").value("vazio"))
            .andExpect(jsonPath("$.failureReason").value(nullValue()));
    }

    @Test void toolsListIncludesNotes() throws Exception {
        mvc.perform(get("/tools")).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(jsonPath("$[0].name").value("clock.now"))
            .andExpect(jsonPath("$[1].name").value("math.add"))
            .andExpect(jsonPath("$[2].name").value("notes.read"))
            .andExpect(jsonPath("$[3].name").value("notes.write"))
            .andExpect(jsonPath("$[4].name").value("text.upper"))
            .andExpect(jsonPath("$[2].description").isNotEmpty())
            .andExpect(jsonPath("$[3].description").isNotEmpty());
    }

    @Test void maxStepsPerRun() throws Exception {
        for (int i = 0; i < 5; i++) REPLIES.add(tool("text.upper", "{\"text\":\"x\"}"));
        start("{\"goal\":\"curto\",\"maxSteps\":2}")
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.failureReason").value("max_steps_exceeded"))
            .andExpect(jsonPath("$.steps.length()").value(2));
        REPLIES.clear();
        for (int i = 0; i < 12; i++) REPLIES.add(tool("text.upper", "{\"text\":\"x\"}"));
        start("{\"goal\":\"padrao\",\"maxSteps\":null}")
            .andExpect(jsonPath("$.failureReason").value("max_steps_exceeded"))
            .andExpect(jsonPath("$.steps.length()").value(8));
        REPLIES.clear();
        for (int i = 0; i < 12; i++) REPLIES.add(tool("text.upper", "{\"text\":\"x\"}"));
        start("{\"goal\":\"longo\",\"maxSteps\":10}").andExpect(jsonPath("$.steps.length()").value(10));
    }

    @Test void maxStepsValidationAndOrder() throws Exception {
        for (String v : new String[] {"0", "21", "2.5", "\"3\"", "true", "[]", "{}"}) {
            start("{\"goal\":\"g\",\"maxSteps\":" + v + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_max_steps"));
        }
        start("{\"goal\":\"  \",\"maxSteps\":0}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_goal"));
        start("{nope").andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_json"));
        start("{\"goal\":\"g\",\"maxSteps\":20}").andExpect(status().isCreated());
        start("{\"goal\":\"g\",\"maxSteps\":1}").andExpect(status().isCreated());
    }

    @Test void listRunsNewestFirstWithStatusFilter() throws Exception {
        String ok1 = startId("{\"goal\":\"primeiro\"}");
        REPLIES.add("blá");
        String bad = startId("{\"goal\":\"segundo\"}");
        String ok2 = startId("{\"goal\":\"terceiro\"}");
        mvc.perform(get("/runs")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(ok2))
            .andExpect(jsonPath("$[0].goal").value("terceiro"))
            .andExpect(jsonPath("$[0].status").value("SUCCEEDED"))
            .andExpect(jsonPath("$[1].id").value(bad))
            .andExpect(jsonPath("$[1].status").value("FAILED"))
            .andExpect(jsonPath("$[2].id").value(ok1));
        mvc.perform(get("/runs").param("status", "FAILED")).andExpect(status().isOk())
            .andExpect(jsonPath("$[*].status", everyItem(is("FAILED"))))
            .andExpect(jsonPath("$[0].id").value(bad));
        mvc.perform(get("/runs").param("status", "SUCCEEDED")).andExpect(status().isOk())
            .andExpect(jsonPath("$[*].status", everyItem(is("SUCCEEDED"))))
            .andExpect(jsonPath("$[0].id").value(ok2))
            .andExpect(jsonPath("$[1].id").value(ok1));
        for (String s : new String[] {"failed", "RUNNING", ""}) {
            mvc.perform(get("/runs").param("status", s))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_status"));
        }
    }
}
