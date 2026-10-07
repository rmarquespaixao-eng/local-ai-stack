# 8. Como saber se um modelo serve (a escada de provas)

Velocidade e benchmark de texto **não preveem** nada do que importa para agente. Dos 17 modelos que
passaram por aqui, muitos eram rápidos e só 4 fecharam a prova difícil. Esta é a metodologia que
usamos — e que você pode reproduzir com fixtures seus.

## 8.1 A escada (3 degraus, pare no primeiro "não")

| Degrau | O que é | Custo | Quem passa |
|---|---|---:|---|
| **1. smoke** | o modelo carrega, gera, faz tool call e mede t/s | ~1 min | quase todos |
| **2. `api-tarefas`** | API pequena em TypeScript, 4 tarefas, **testes de aceite ocultos** | ~15 min | modelos razoáveis |
| **3. `agente-spring-ai`** | agente em Java: 3 módulos, Bean/MVC, regras rígidas (`-Werror`), 8 tarefas, 22 testes ocultos | ~12 min | **só os bons — é o degrau que separa** |

**Veredito:** `APROVADO` = passou 2 e 3 · `PARCIAL` = só o 2 · `REPROVADO` = nem o 2.

Se um modelo reprova no 3, registre **como** reprovou (loop? contrato errado? parou sem chamar
ferramenta?). É a informação que evita repetir o teste daqui a 3 meses — a tabela em
[docs/02](02-modelos.md#23-reprovados--o-que-não-tentar-e-por-quê) existe por causa disso.

## 8.2 Aceite oculto: a única coisa que torna o teste válido

Um modelo pode "terminar a tarefa" escrevendo arquivos que não passam nos testes. Então:

```
fixture/
  repo.bundle          # git bundle do repositório-base, branch `fixture` (o que o modelo recebe)
  fixture.json         # timeout, comandos de build/teste, o que está protegido
  acceptance/          # ⚠️ NUNCA vai para o modelo — só para o avaliador
    tests/…            # os testes que decidem o veredito
    reference.patch    # opcional: uma solução known-good para validar o oráculo
```

**Validar o oráculo antes de usar:** rode os testes contra a referência (tem que dar 100%) e contra o
repositório base (tem que dar ~0%). Se a base passar, o teste não mede nada. Nos nossos fixtures de
planejamento: referência 30/30, base 21/30 — daí o teste ser útil.

Armadilhas que os testes precisam pegar (e pegaram): resposta HTTP errada (404 vs 200), contrato de
tipo fora do lugar (`UseTool` aninhado em `Decision`), bean duplicado que impede o Spring de subir,
`@SpringBootTest(classes=…)` ignorando `@TestConfiguration` aninhada (aí o teste usa o **modelo real**
e você acha que o modelo local está trocando de modelo sozinho).

## 8.3 Watchdog: as 4 formas de falhar de um agente local

Rodar e esperar é perder a noite. Todo run precisa de um vigia com regras explícitas:

| Modo de falha | Critério que usamos | O que significa |
|---|---|---|
| `loop` | 4 chamadas **idênticas** seguidas | sampling agressivo demais, ou greedy em reasoning |
| `error_loop` | o **mesmo erro** 6× | modelo não consegue autocorrigir; em Java, quase sempre contrato de tipos |
| `no_action` | 10 min sem nenhum `write`/`edit` | narrou o plano e parou (o defeito clássico do Hermes/LFM) |
| `no_progress` | 15 min sem commit | mexendo sem produzir |
| `timeout` | api-tarefas 1800 s · spring-ai 3600 s | teto do fixture |

E o mais importante da categoria **não é o modelo**: MCP remoto travando a inicialização, swap de
modelo no meio do teste, ou a GPU pedindo arrego. Se o watchdog disparar, confira o hardware/isolamento
antes de culpar o candidato ([docs/07](07-problemas.md#76-método-de-medição-os-erros-que-quase-nos-fizeram-decidir-errado)).

## 8.4 Isolamento e regras do protocolo

1. **Config isolado por run**: cópia do `opencode.json` com todos os MCPs desligados via
   `XDG_CONFIG_HOME`. Sem isso, um MCP lento contamina o tempo e o veredito.
2. **Não edite o config do llama-swap durante a bateria** (`-watch-config` derruba o stream).
3. **Duas rodadas** no mínimo para promover. Nosso principal aprovou 2 de 3.
4. **Promova com evidência, não com benchmark de texto.** HumanEval não mede o eixo multi-arquivo.
5. **Roda noturna com parada automática** em sinal de hardware (MCE no journal, timeout de GPU, VRAM
   livre abaixo do mínimo). Uma fila sem isso queima a noite e dados errados.
6. **Reprovado: registra e arquiva.** Mover para `REPROVADOS/` + comentar o perfil com
   `# [REPROVADO <data> — <motivo>]`. Apagar o GGUF só quando alguém mandar.

## 8.5 Como montar fixtures equivalentes (sem os nossos)

Os nossos repositórios de teste não vão neste repo (são privados), mas a receita é curta e o resultado
é o que importa: um serviço pequeno, build forte, testes que o modelo não vê.

**Degrau 2 — `api-tarefas` (TypeScript):**
- Node 24 com TypeScript nativo (sem build step), `package.json` com `test` e `typecheck`.
- 4 tarefas em `TASKS.md` (ex.: criar rota, validar payload, adicionar campo, corrigir estado).
- 6 testes de aceite por HTTP (status code, forma do JSON, persistência) que falham no repositório base.

**Degrau 3 — `agente-spring-ai` (Java):**
- 3 módulos (`core`, `spring-ai`, `app`), Spring Boot 4 + Spring AI 2.0.1 + Jackson 3, `-Werror`.
- 8 tarefas que exigem mexer em contrato de tipos + bean + prompt.
- 22 testes ocultos (11 + 7 + 4 por módulo) com um fake do `ChatModel` — **e um teste E2E opcional que
  usa o próprio modelo local como cérebro**; é o que prova que o agente funciona de ponta a ponta.
- Armadilha proposital: um teste que exige que chaves dentro de um prompt sejam preservadas (`{` vs
  `{{`). Modelos que "arredondam" o prompt caem aí.
- Detalhe que queima: no Spring AI 2.0, `base-url` precisa do sufixo `/v1`.

**Provas de planejamento (o degrau 4, que criamos depois):**
- `planejar-tarefas`: o modelo recebe só um `FEATURE.md` de produto (3 frases), escreve `SPEC.md` +
  `TASKS.md`, e **outro** modelo executa; 8 testes HTTP decidem. Referência 8/8, base 1/8.
- `planejar-agente`: o mesmo em uma feature grande em Java **sem quebrar compatibilidade** com 21
  testes existentes. Referência 30/30, base 21/30.
- Por que dois modelos? Porque "planejar" e "executar" são competências diferentes: o mesmo modelo que
  planejou 8/8, executa melhor **sem** raciocínio (~45% mais rápido com o mesmo resultado).

## 8.6 Script mínimo de um run

Sem framework, para você copiar:

```bash
PERFIL=strata-flash-next
REPO=/tmp/eval-$PERFIL
rm -rf "$REPO"; git clone --branch fixture ~/bench/fixtures/api-tarefas/repo.bundle "$REPO"
cd "$REPO"
echo '{"model":"llama-cpp/'"$PERFIL"'"}' > opencode.json
echo 'opencode.json' >> .git/info/exclude

# config isolado: sem MCPs, para nada externo contaminar o tempo
XDG_CONFIG_HOME=/tmp/xdg-$PERFIL timeout 1800 opencode run --standalone --auto \
  --model "llama-cpp/$PERFIL#off" --format json "$(cat acceptance/../PROMPT.txt)" \
  > "run-$PERFIL.jsonl" 2>&1

# avalia: tarefas marcadas, commits, arquivos protegidos, e o aceite oculto
git log --oneline origin/fixture..HEAD
cp -r ~/bench/fixtures/api-tarefas/acceptance/tests tests/ && pnpm test
```

O `--model "$PERFIL#off"` é o ponto: o mesmo modelo, duas competências. Um run de execução com
raciocínio ligado não é comparável a um sem.

## 8.7 O que medimos e onde ficaram as dúvidas

- **Aprovados** (executor): strata-flash-next (instruct e thinking), swift-1.5-27b, qwen3.8-gsq-s,
  muse-glimmer-30b (só thinking). Números em [docs/02](02-modelos.md).
- **Planejamento**: o Flash-Next foi o primeiro modelo local que planejou ponta a ponta com aceite
  verde aqui — com **revisão humana/do modelo grande** no plano. A única falha de 19/30 era uma linha
  do plano; corrigida, 30/30.
- **Em aberto**: IQ3_XXS no Strata com 54 GB de RAM, Swift Flash-Next como executor (1 teste curto,
  promissor, sem veredito formal), Qwen3.6-35B (parcial), coder-next, Mistral Small 4, Devstral.
  Não publicamos veredito do que não foi medido.
