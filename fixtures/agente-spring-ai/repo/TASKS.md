# TASKS — execute em ordem; um commit por tarefa

Regras:
- Leia `SPEC.md` antes de começar e consulte a seção indicada em cada tarefa.
- NÃO altere `build.gradle.kts`, `settings.gradle.kts` nem `gradle/`. Não leia `~/.gradle`, `build/` ou `.gradle/`.
- Ao fim de cada tarefa: `./gradlew build --console=plain -q` precisa passar (warning = erro).
- Então marque `[x]` aqui e faça `git add -A && git commit -m "<mensagem indicada>"`.
- Se o build falhar, leia a mensagem de erro, corrija e rode de novo. Não pule tarefas.

- [ ] T1 — **Domínio** (SPEC §3.1): `InvalidGoalException`, `Goal`, `Decision`, `Step`, `RunStatus`, `AgentRun`, `AgentPolicy` em `agent-core/src/main/java/dev/agente/core/domain/`. Testes: `GoalTest` (strip, limites 500/501, null, branco), `AgentPolicyTest` (defaults, inválidos), `AgentRunTest` (succeeded/failed, imutabilidade de `steps`). Commit: `feat(core): domain model`
- [ ] T2 — **Portas + registry** (SPEC §3.2, §3.3): `Tool`, `Brain`, `RunRepository`, `IdGenerator`, `AgentContext`, `ToolRegistry`. Teste: `ToolRegistryTest` (ordenação, find, duplicado). Commit: `feat(core): ports and tool registry`
- [ ] T3 — **Motor** (SPEC §3.3, algoritmo normativo): `AgentEngine`. Teste `AgentEngineTest` com `Brain` roteirizado (fila de decisões) cobrindo: finish imediato; tool + finish (step e histórico passado ao brain); `unknown_tool`; `tool_error`; `too_many_errors`; sucesso zera a sequência de erros; `max_steps_exceeded`; `brain_error: <msg>`; `brain_error: null_decision`. Commit: `feat(core): autonomous agent engine`
- [ ] T4 — **Caso de uso** (SPEC §3.4): `AgentService` + `AgentServiceTest` (id gerado, goal com strip, salvo e encontrado, goal inválido propaga). Commit: `feat(core): agent service use case`
- [ ] T5 — **Parser** (SPEC §4.1): `InvalidDecisionException`, `DecisionParser` + `DecisionParserTest` (as 7 regras, incluindo `<think>`, cercas ```` ```json ````, args numéricos, args ausentes, casos inválidos). Commit: `feat(spring-ai): decision parser`
- [ ] T6 — **Cérebro Spring AI** (SPEC §4.1): `PromptFactory`, `SpringAiBrain` + testes com `ChatModel` fake (veja "Notas de versão"): prompt contém ferramentas/objetivo/histórico; chaves `{}` chegam intactas; resposta inválida propaga `InvalidDecisionException`. Commit: `feat(spring-ai): chat model brain`
- [ ] T7 — **Ferramentas + composição** (SPEC §5.1, §5.2): `AddTool`, `UpperTool`, `ClockTool`, `InMemoryRunRepository`, `AgentConfig`, `AgentApplication`, `application.yml`. Testes unitários das 3 ferramentas (`ClockTool` com `Clock.fixed`). Commit: `feat(app): tools and wiring`
- [ ] T8 — **API REST** (SPEC §5.3): `RunController` + `RunControllerTest` (`@SpringBootTest` + `@AutoConfigureMockMvc` + `@TestConfiguration` com `@Bean @Primary ChatModel` roteirizado) cobrindo TODAS as linhas da tabela. Commit: `feat(app): runs REST API`

Pronto quando as 8 estiverem `[x]`, `./gradlew build` verde e `git status` limpo.
