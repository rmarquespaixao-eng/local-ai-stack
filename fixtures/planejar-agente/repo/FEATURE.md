# Pedido de feature — Agente v2: bloco de notas por run, limite de passos por run e listagem de runs

> Escrito pelo dono do produto. O que vale é o comportamento descrito aqui.
> `SPEC.md` e `TASKS.md` atuais descrevem a v1 (entregue, `./gradlew build` verde).

## Contexto
Os agentes perdem o fio em objetivos de vários passos: não têm onde guardar resultados
intermediários. Também precisamos limitar runs caros individualmente e ver os runs já feitos.

## 1. Bloco de notas do agente (por run)
Duas ferramentas novas, disponíveis para o modelo como as outras:

| Ferramenta | Args | Comportamento |
|---|---|---|
| `notes.write` | `key`, `value` | grava (ou sobrescreve) a nota `key` com `value`; retorna `ok` |
| `notes.read` | `key` | retorna o valor da nota `key` |

- `key` ausente ou em branco, ou `value` ausente (no `notes.write`) → o passo falha com saída
  `tool_error: invalid_args` (passo com `ok=false`, como as outras ferramentas). `value` pode ser texto vazio.
- `notes.read` de uma chave que não existe → passo falha com saída `tool_error: note_not_found`.
- As notas são **isoladas por run**: um run nunca vê as notas de outro, e todo run começa sem notas.
- O run devolvido pela API passa a ter o campo `notes`: objeto JSON com o estado final das notas
  (`{}` quando não há nenhuma). Isso vale para runs SUCCEEDED e FAILED, no POST e no GET.
- **O modelo precisa enxergar as notas a cada turno**: a mensagem de usuário enviada ao modelo contém
  a linha `Notas: (vazio)` quando não há notas; senão a linha `Notas:` seguida de uma linha
  `- <key> = <value>` por nota, em ordem alfabética de `key`.
- `GET /tools` passa a listar as 5 ferramentas (continua ordenado por nome, com descrição não vazia).

## 2. Limite de passos por run
- `POST /runs` aceita o campo opcional `maxSteps`: inteiro JSON de 1 a 20. Ausente ou `null` → usa o padrão
  configurado (`agent.max-steps`, hoje 8).
- Qualquer outro valor (`0`, `21`, `2.5`, `"3"`, `true`, ...) → `400 {"error":"invalid_max_steps"}`.
- Ordem das validações do POST: JSON inválido (`invalid_json`) → objetivo inválido (`invalid_goal`) → `invalid_max_steps`.
- O limite de erros consecutivos continua o configurado.

## 3. Listar runs — `GET /runs`
- `200` com um array de resumos `{"id","goal","status"}`, do run **mais recente para o mais antigo**.
- Filtro opcional `?status=SUCCEEDED` ou `?status=FAILED` (exatamente assim). Outro valor →
  `400 {"error":"invalid_status"}`.

## O que NÃO pode mudar
- Tudo o que a API já faz (`POST /runs`, `GET /runs/{id}`, `GET /tools`, erros, header `Location`, JSON do run
  — que só ganha o campo `notes`).
- As classes Java que outros times já usam continuam compilando e se comportando igual, com as mesmas
  assinaturas públicas da SPEC v1: `Goal`, `Decision`, `Step`, `RunStatus`, `AgentPolicy` (inclusive `defaults()`),
  `AgentRun` (inclusive o construtor com os 6 campos atuais e `succeeded`/`failed` com os parâmetros atuais),
  `AgentContext` (construtor com `goal, tools, history`), `ToolRegistry`, `AgentEngine` (construtor e `run(id, goal)`),
  `AgentService` (construtor, `start(rawGoal)`, `find(id)`), `DecisionParser`, `PromptFactory.system(tools)`,
  `PromptFactory.user(goal, history)`, `SpringAiBrain`, as portas `Tool`, `Brain`, `RunRepository`, `IdGenerator`.
  Pode **acrescentar** construtores, métodos e sobrecargas; não pode remover nem mudar os existentes.
- Restrições técnicas da SPEC v1 (módulos, dependências, Jackson 3, `-Werror`, imutabilidade, hexagonal).
