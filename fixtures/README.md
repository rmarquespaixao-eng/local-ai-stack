# Os 4 fixtures de teste

Os repositórios de prova que usamos para decidir o que entra na stack. Cada fixture traz **o repo
base** (o que o modelo recebe), **o aceite oculto** (o que decide o veredito), o `fixture.json`
(configuração) e os prompts.

| Fixture | O que prova | Tarefas | Aceite | Tempo-limite | Linha de base medida |
|---|---|---:|---:|---:|---|
| `api-tarefas` | executar um plano pequeno em TypeScript (Node 24, TS nativo, sem dependências) | 4 | 6 testes | 1800 s | 135–170 s · 6/6 |
| `agente-spring-ai` | executar um plano exigente em Java (3 módulos, Boot 4 + Spring AI 2 + Jackson 3, `-Werror`) | 8 | 22 testes | 3600 s | ~601–706 s · 22/22 |
| `planejar-tarefas` | **planejar**: `FEATURE.md` → `SPEC.md`/`TASKS.md` → outro modelo executa | — | 8 testes HTTP | 1800 s | plano em ~6 min · 8/8 |
| `planejar-agente` | planejar feature grande em Java **sem quebrar compatibilidade** | — | 30 (21 regressão + 9) | 3600 s | 19/30 · 30/30 com 1 linha corrigida no plano |

## Layout

```
fixtures/<nome>/
  repo/                 # branch fixture: o que o modelo vê (SPEC.md, TASKS.md, build, código base)
  acceptance/           # testes de aceite — esta pasta não vai para o modelo
  fixture.json          # setup, protect, timeout, verify_cmd, baseline
  PROMPT-EXECUTOR.txt   # prompt de execução (plan → execute)
  PROMPT-PLANEJADOR.txt # só nos de planejamento
  referencia.patch      # só no planejar-agente: solução known-good (valida o oráculo)
```

## Rodar sem o nosso harness

```bash
FX=api-tarefas
D=/tmp/run-$FX

./fixtures/bootstrap.sh "$FX" "$D"        # cria $D como repositório git na branch `fixture`
cd "$D"
echo '{"model":"llama-cpp/strata-flash-next"}' > opencode.json
echo opencode.json >> .git/info/exclude
pnpm install --frozen-lockfile             # nos de Java: ./gradlew tasks

opencode run --standalone --auto --model 'llama-cpp/strata-flash-next#off' \
  "$(cat /caminho/deste/repo/fixtures/PROMPT-EXECUTOR.txt)"

# avalie: o aceite entra só agora, e depois é removido
/caminho/deste/repo/fixtures/verify.sh "$FX" "$D"
```

O `verify.sh` também avisa se o modelo tocou em algum arquivo do `protect` do `fixture.json` — o que
invalida o run (testamos isso: modelos reprovados costumavam consertar o `tsconfig` em vez do código).

## O que faz o teste valer alguma coisa

1. **Aceite fora do repo.** Se o modelo vê os testes, ele otimiza para eles e o veredito não significa
   nada. No nosso fluxo, `acceptance/` só entra na hora de avaliar (e os testes do repo base são
   outros, esses sim visíveis).
2. **Valide o oráculo antes de confiar no número.** `referencia.patch` (planejar-agente) passa 30/30;
   o repo base passa 21/30. Se a base tirasse 30/30, o teste não estaria medindo nada — e já pegamos
   um caso assim, em que um teste existente esperava `404` numa rota que a feature mudava.
3. **Respeite o `protect`.** Mexer em `package.json`, `tsconfig.json` ou nos `*.gradle.kts` é o atalho
   mais comum para "fazer o build passar" sem fazer a tarefa.
4. **Meça tempo, commits e o aceite.** Um run 8/8 nas tarefas com 17/22 de aceite é PARCIAL. Um run
   aprovado com 45 s de loop em comando de saída vazia é um comportamento que precisa ser registrado.
5. **Duas rodadas.** Nosso principal passou em 2 de 3 — com n=1 a variância engana fácil.
6. **Não edite o config do llama-swap durante o run.** O reload interrompe o stream e a rodada é
   perdida por motivo que não é do modelo.

## Detalhes de cada fixture

### `api-tarefas` (TypeScript)
API de tarefas com 2 endpoints, arquitetura hexagonal (`domain` sem I/O, `adapters`, `http`), Node 24
executando TS nativo (`type stripping`), **zero dependência de runtime**. As 4 tarefas vão do domínio
ao bootstrap do servidor, com um commit por tarefa. Armadilhas embutidas: imports relativos exigem
extensão `.ts`; `erasableSyntaxOnly` proíbe `enum`/`namespace`/parameter properties; `GET /tasks/:id`
deve devolver 404 com `{ "error": "not_found" }`.

### `agente-spring-ai` (Java)
Agente com loop de decisão (`Decision.UseTool` / `Decision.Finish` aninhados), 3 módulos
(`agent-core`, `agent-spring-ai`, `agent-app`), Gradle com `-Werror`, Spring Boot 4 + Spring AI 2.0.1 +
Jackson 3. Os 22 testes ocultos cobrem domínio, parser de decisão (com um caso que exige que `{` e `}`
do prompt fiquem intactos — quem "normaliza" o prompt cai aí) e o contexto Spring. Nota de versão que
queima tempo: no Spring AI 2.0 o `base-url` precisa do sufixo `/v1`.

### `planejar-tarefas` e `planejar-agente`
Aqui o modelo avaliado não escreve código: ele reescreve `SPEC.md`/`TASKS.md` a partir de um
`FEATURE.md` de produto, e **outro** modelo (mesmo motor, esforço de raciocínio desligado) executa. O
aceite julga o comportamento final, então um plano vago aparece como falha de execução. No
`planejar-agente`, 21 dos 30 testes são de regressão: plano que quebra a API existente perde mesmo
implementando a feature.

## Se você publicar estes fixtures

Eles são nossos depois de publicados: modelos novos que treinaram com dados da internet podem tê-los
visto. O que ainda funciona é o **padrão** (aceite oculto + `protect` + oráculo validado) — troque os
nomes de rota, as mensagens de erro e um ou dois casos de borda antes de usar para decidir algo
importante.
