# SPEC — Agente de IA autônomo com Spring AI

Fonte da verdade. Implemente EXATAMENTE os contratos abaixo (pacotes, nomes, assinaturas,
mensagens). Nada além disto.

## 1. Visão geral
Um agente que recebe um **objetivo** em texto e o persegue sozinho, num loop
*decidir → executar ferramenta → observar*, até encerrar ou estourar limites.
O "cérebro" é um LLM acessado via **Spring AI** (`ChatModel`), que a cada turno responde
uma **decisão em JSON**. As ferramentas são plugáveis. Tudo é exposto por uma API REST.

## 2. Stack e restrições (já configuradas — NÃO altere os `build.gradle.kts`/`settings.gradle.kts`)
- Java 25 (bytecode `--release 25`, compilado com JDK 27), Gradle 9.8 (`./gradlew`), compilação com `-Xlint:all -Werror` (warning = erro).
- Spring Boot **4.0.8**, Spring AI **2.0.1**, Jackson **3** (pacotes `tools.jackson.*`, NÃO `com.fasterxml.jackson.databind`), JUnit 5 + AssertJ.
- Arquitetura hexagonal em **3 módulos Gradle** (dependências só apontam para dentro):

| Módulo | Pacote raiz | Pode depender de |
|---|---|---|
| `agent-core` | `dev.agente.core` | NADA além do JDK (sem Spring, sem Jackson, sem I/O) |
| `agent-spring-ai` | `dev.agente.springai` | `agent-core`, `spring-ai-client-chat`, Jackson 3 |
| `agent-app` | `dev.agente.app` | `agent-core`, `agent-spring-ai`, Spring Boot WebMVC, starter OpenAI |

- Imutabilidade: records com cópia defensiva (`List.copyOf`, `Map.copyOf`) nos construtores compactos indicados.
- Testes colocados em `src/test/java/<mesmo pacote>/<Classe>Test.java` de cada módulo.

### Notas de versão (armadilhas conhecidas)
- Jackson 3: `tools.jackson.databind.json.JsonMapper`, `tools.jackson.databind.JsonNode`, exceção base `tools.jackson.core.JacksonException` (unchecked). Em `JsonNode`: `isString()`, `asString()`, `isObject()`, `properties()` (Set de `Map.Entry<String, JsonNode>`).
- Spring AI 2.0.1: fake de modelo em teste = implementar `org.springframework.ai.chat.model.ChatModel` e só `ChatResponse call(Prompt prompt)`, devolvendo `new ChatResponse(List.of(new Generation(new AssistantMessage(texto))))`. `Prompt#getSystemMessage().getText()` / `Prompt#getUserMessage().getText()`.
- Spring Boot 4: `@AutoConfigureMockMvc` está em `org.springframework.boot.webmvc.test.autoconfigure`. `@SpringBootTest`/`@TestConfiguration` em `org.springframework.boot.test.context`.
- `-Werror` + `-Xlint:all`: classes de exceção precisam de `private static final long serialVersionUID = 1L;`.

## 3. `agent-core`

### 3.1 `dev.agente.core.domain`
```java
public final class InvalidGoalException extends IllegalArgumentException { /* mensagem: "invalid_goal" */ }

public record Goal(String value) {
    public static final int MAX_LENGTH = 500;
    // compacto: null -> InvalidGoalException; value = value.strip(); vazio ou > 500 -> InvalidGoalException
}

public sealed interface Decision {
    record UseTool(String tool, Map<String, String> args) implements Decision {} // args = Map.copyOf(args)
    record Finish(String answer) implements Decision {}
}

public record Step(int index, String tool, Map<String, String> args, String output, boolean ok) {} // args = Map.copyOf
public enum RunStatus { SUCCEEDED, FAILED }

public record AgentRun(String id, String goal, RunStatus status, List<Step> steps, String answer, String failureReason) {
    // steps = List.copyOf(steps)
    public static AgentRun succeeded(String id, Goal goal, List<Step> steps, String answer); // failureReason = null
    public static AgentRun failed(String id, Goal goal, List<Step> steps, String reason);    // answer = null
}

public record AgentPolicy(int maxSteps, int maxConsecutiveErrors) {
    // qualquer valor < 1 -> IllegalArgumentException("invalid_policy")
    public static AgentPolicy defaults(); // (8, 2)
}
```

### 3.2 `dev.agente.core.port`
```java
public interface Tool { String name(); String description(); String execute(Map<String, String> args); }
public interface Brain { Decision decide(AgentContext context); }
public interface RunRepository { void save(AgentRun run); Optional<AgentRun> findById(String id); }
@FunctionalInterface public interface IdGenerator { String next(); }
```

### 3.3 `dev.agente.core.engine`
```java
public record AgentContext(Goal goal, List<Tool> tools, List<Step> history) {} // List.copyOf em ambas

public final class ToolRegistry {
    public ToolRegistry(List<? extends Tool> tools); // nome repetido -> IllegalArgumentException("duplicate_tool: <nome>")
    public Optional<Tool> find(String name);
    public List<Tool> all();                          // imutável, ordenada por name()
}

public final class AgentEngine {
    public AgentEngine(Brain brain, ToolRegistry tools, AgentPolicy policy);
    public AgentRun run(String runId, Goal goal);
}
```

**Algoritmo de `AgentEngine.run` (normativo):**
```
history = [], errors = 0
enquanto history.size() < policy.maxSteps:
    decision = brain.decide(new AgentContext(goal, tools.all(), history))
        RuntimeException e  -> FAILED, reason "brain_error: " + e.getMessage()
        decision == null    -> FAILED, reason "brain_error: null_decision"
    Finish(answer)          -> SUCCEEDED com answer
    UseTool(tool, args), index = history.size() + 1:
        ferramenta inexistente -> Step(index, tool, args, "unknown_tool: " + tool, false)
        execute lança RuntimeException e -> Step(index, tool, args, "tool_error: " + e.getMessage(), false)
        sucesso -> Step(index, tool, args, <retorno de execute>, true)
    history += step; errors = step.ok ? 0 : errors + 1
    errors >= policy.maxConsecutiveErrors -> FAILED, reason "too_many_errors"
fim do laço -> FAILED, reason "max_steps_exceeded"
```
Todo `AgentRun` devolvido carrega `steps` = histórico acumulado até ali.

### 3.4 `dev.agente.core.application`
```java
public final class AgentService {
    public AgentService(AgentEngine engine, RunRepository runs, IdGenerator ids);
    public AgentRun start(String rawGoal);   // new Goal(rawGoal) (propaga InvalidGoalException) -> engine.run(ids.next(), goal) -> runs.save -> retorna
    public Optional<AgentRun> find(String id);
}
```

## 4. `agent-spring-ai` (`dev.agente.springai`)

### 4.1 Protocolo de decisão (o que o LLM deve responder)
```json
{"action":"tool","tool":"<nome>","args":{"<chave>":"<valor>"}}
{"action":"finish","answer":"<resposta final>"}
```

```java
public final class InvalidDecisionException extends RuntimeException { /* mensagem SEMPRE "invalid_decision" */ }

public final class DecisionParser {
    public static Decision parse(String raw);
}
```
Regras de `parse`:
1. `raw == null` → `InvalidDecisionException`.
2. Remover todo bloco `<think>...</think>` (multilinha) e aplicar `strip()`.
3. Se começar com ```` ``` ````: descartar a 1ª linha (a cerca, ex. ```` ```json ````) e o que vier a partir do último ```` ``` ````; `strip()`.
4. O resultado precisa ser um **objeto** JSON; JSON inválido ou não-objeto → `InvalidDecisionException`.
5. `action == "finish"`: `answer` precisa ser string JSON → `Decision.Finish(answer)`; senão inválido.
6. `action == "tool"`: `tool` precisa ser string não-branca. `args` é opcional (ausente/`null` → `Map.of()`); se presente e não for objeto → inválido. Valores string entram como estão; qualquer outro valor entra pelo texto JSON (`2` → `"2"`, `true` → `"true"`).
7. Qualquer outro `action` → `InvalidDecisionException`.

```java
public final class PromptFactory {
    public static String system(List<Tool> tools);
    public static String user(Goal goal, List<Step> history);
}
```
- `system`: instrui a responder APENAS um objeto JSON; contém os DOIS formatos literais da 4.1 (com `"action":"tool"` e `"action":"finish"`) e uma linha `- <name>: <description>` por ferramenta.
- `user`: contém `Objetivo: <goal.value()>` e, por passo, uma linha `#<index> <tool> <args> -> <output>`; sem passos, `(vazio)`.

```java
public final class SpringAiBrain implements Brain {
    public SpringAiBrain(ChatModel model);   // usa ChatClient.create(model)
    public Decision decide(AgentContext ctx); // system + user da PromptFactory -> .call().content() -> DecisionParser.parse
}
```
Textos com `{` `}` (JSON no histórico ou no objetivo) precisam chegar intactos ao modelo.

## 5. `agent-app` (`dev.agente.app`)

### 5.1 Ferramentas (`dev.agente.app.tools`, cada uma `@Component implements Tool`)
| Classe | `name()` | Comportamento |
|---|---|---|
| `AddTool` | `math.add` | args `a`, `b` inteiros (`Long.parseLong` após strip); retorna a soma como texto. Ausente/branco/não numérico → `IllegalArgumentException("invalid_args")` |
| `UpperTool` | `text.upper` | arg `text` obrigatório (ausente/branco → `invalid_args`); retorna `toUpperCase(Locale.ROOT)` |
| `ClockTool` | `clock.now` | sem args; `Instant.now(clock).toString()`, com `java.time.Clock` injetado no construtor |
`description()` não vazia em todas.

### 5.2 Composição (`dev.agente.app.config`)
- `InMemoryRunRepository implements RunRepository` (`ConcurrentHashMap`).
- `@Configuration AgentConfig` com `@Bean`s: `Clock` (`systemUTC`), `ToolRegistry` (recebe `List<Tool>`), `AgentPolicy` (`agent.max-steps` padrão 8, `agent.max-consecutive-errors` padrão 2), `Brain` = `new SpringAiBrain(chatModel)`, `AgentEngine`, `RunRepository`, `IdGenerator` (`UUID.randomUUID().toString()`), `AgentService`.
- `src/main/resources/application.yml`: `spring.ai.openai.base-url: http://127.0.0.1:8082/v1` (Spring AI 2.0 não acrescenta `/v1`), `api-key: local`, `chat.options.model: qwen3.8-gsq-s`, `temperature: 0.2`; `agent.max-steps: 8`, `agent.max-consecutive-errors: 2`.
- `AgentApplication` (`@SpringBootApplication`, `main`) em `dev.agente.app`.

### 5.3 API REST (`dev.agente.app.web.RunController`)
Execução **síncrona**: o POST só responde quando o run termina.

| Método e rota | Condição | Status | Corpo |
|---|---|---|---|
| `POST /runs` body `{"goal": "..."}` | run executado (SUCCEEDED ou FAILED) | 201 + header `Location: /runs/<id>` | o `AgentRun` |
| `POST /runs` | goal ausente/branco/> 500 | 400 | `{"error":"invalid_goal"}` |
| `POST /runs` | corpo não é JSON válido | 400 | `{"error":"invalid_json"}` |
| `GET /runs/{id}` | existe | 200 | o `AgentRun` |
| `GET /runs/{id}` | não existe | 404 | `{"error":"not_found"}` |
| `GET /tools` | — | 200 | `[{"name":"...","description":"..."}]` ordenado por `name` |

JSON do `AgentRun` = nomes dos componentes do record (`id, goal, status, steps[{index, tool, args, output, ok}], answer, failureReason`), com `null` explícito em `answer`/`failureReason` quando ausentes.

## 6. Critério de aceite
`./gradlew build` verde (compila com `-Werror` e roda todos os testes), `git status` limpo,
um commit por tarefa do `TASKS.md`.
