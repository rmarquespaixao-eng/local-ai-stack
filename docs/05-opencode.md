# 5. OpenCode — o agente que usa o modelo local

O llama-swap dá uma API OpenAI-compatível. Falta o **executor**: o programa que lê seu repositório,
chama o modelo, edita arquivos, roda testes e commita. Usamos o **OpenCode v2** (`v2.0.23`).

Serve qualquer cliente que fale `/v1/chat/completions` (Cline, Continue, aider…). Os detalhes
aqui são do OpenCode, mas as armadilhas de contexto/sampling valem para todos.

## 5.1 Provider local

`~/.config/opencode/opencode.json`:

```json
{
  "provider": {
    "llama-cpp": {
      "npm": "@ai-sdk/openai-compatible",
      "name": "Local (llama-cpp :8082)",
      "options": { "baseURL": "http://127.0.0.1:8082/v1" },
      "models": {
        "strata-flash-next": {
          "name": "Flash-Next 125B · local",
          "limit": { "context": 131072, "output": 32768 },
          "variants": {
            "off":   { "reasoningEffort": "none" },
            "low":   { "reasoningEffort": "low" },
            "medium":{ "reasoningEffort": "medium" },
            "xhigh": { "reasoningEffort": "xhigh" }
          }
        }
      }
    }
  }
}
```

- `openai-compatible` é o que funciona. Provider "ollama"/"lmstudio" pressupõem outro protocolo.
- **Use o llama-swap (:8082), não a porta do backend direto.** A porta do backend muda a cada troca
  de modelo; o llama-swap é a porta fixa.
- `limit.context` deve bater com o `-c`/`--max-context` real (confirme em `/props`). Errar para cima
  = `exceed_context_size_error` no meio da sessão; errar para baixo = você joga contexto fora.

## 5.2 A armadilha que custou um dia inteiro: `variants` é objeto, não array

No OpenCode **v2**, `variants` é um **objeto** (`{"id": {settings}}`). Quem vem do v1 com array
(`[{id, settings}]`) vê o pior bug possível: como o provider tem `additionalProperties: false`, **uma
entrada malformada descarta o provider inteiro** — os 19 modelos locais somem da lista e toda sessão
dá `Model unavailable`. E restart **não** resolve.

```bash
opencode api GET /api/config     # mostra os providers EFETIVOS (depois da normalização)
# no log: "configuration normalization diagnostic … kind=invalid"
opencode reload                  # é reload, não restart, que refaz a normalização
```

Também: **`opencode mcp list` no v2 mente** (devolve vazio mesmo com MCPs ativos), e
**`OPENCODE_CONFIG`/`OPENCODE_CONFIG_DIR` não funcionam** para isolar config por cliente — para
isso, `XDG_CONFIG_HOME`.

## 5.3 Variantes de raciocínio

`llama-cpp/strata-flash-next#xhigh` seleciona modelo+variante. Cadeia até o motor:

```
OpenCode → openai-compatible emite `reasoning_effort` → llama.cpp injeta em chat_template_kwargs
→ o Jinja do template lê `reasoning_effort`  (no Strata: o frontend mapeia low/medium/high→xhigh)
```

Dois fatos que economizam tempo:

- O `model` raiz do config **não retém** a variante; é preciso pedi-la por chamada (`#xhigh`) ou no
  `/models` da TUI.
- Perfil com `--reasoning off` + `enable_thinking:false` **ignora** as variantes. Se o seletor não
  faz nada, procure isso no cmd antes de culpar o cliente.
- Precisa de restart do **serviço** para aplicar mudanças de provider (`opencode service restart`),
  mas **nunca com uma TUI aberta** — a TUI perde o servidor e congela.

## 5.4 Escolher o modelo no projeto (a TUI não tem `-m`)

```bash
echo '{"model": "llama-cpp/strata-flash-next"}' > meu-repo/opencode.json
echo 'opencode.json' >> meu-repo/.git/info/exclude      # sem sujar o .gitignore do projeto
cd meu-repo && opencode --auto
```

`--auto` aprova permissões; sem ele a TUI para a cada comando pedindo confirmação — o que, com um
modelo local de 45 t/s, parece que "travou".

Headless (é assim que rodo baterias):

```bash
opencode run --standalone --auto --model 'llama-cpp/strata-flash-next#off' --format json "<prompt>"
```

⚠️ `opencode run` usa **`$PWD`**, não um `--cwd`. Se quiser rodar em outro diretório, `cd` antes.

## 5.5 Sincronizar a lista de modelos com o llama-swap

Manter 17 nomes na mão é receita para divergência. [`scripts/sync-opencode-models.py`](../scripts/sync-opencode-models.py)
lê `/v1/models` do llama-swap e escreve no config:

- acrescenta o que apareceu, remove o que sumiu;
- **preserva** o que é seu: `limit`, `variants`, `cost` por modelo;
- se o llama-swap estiver fora do ar, **mantém a lista atual** (não apaga tudo por um timeout).

Rode com `--dry-run` primeiro. A unit `opencode-sync-models.timer` faz isso a cada 6 h e no login —
útil porque o config do llama-swap muda mais que o do OpenCode.

## 5.6 O que ocupa o contexto (e por que não vale cache semântico)

Medimos no SQLite do OpenCode (`~/.local/share/opencode/opencode.db`, campo `tokens.cache.read`):

| Mede | Nosso número |
|---|---|
| Hit de **prefix cache** (llama.cpp) | **96,8%** das chamadas |
| Chamadas sem prefix cache | 4 em 305 (1,3%) |
| Chamadas por mensagem do usuário | **10 a 49** |
| Fração de chamadas que são "meio de loop" (`tool_calls`) | **93%** (291/313) |
| Tamanho de prompt p50 | ~75k tokens |

Consequências práticas:

1. **Prefix cache é o que te atende.** Ele depende de o prefixo não mudar: `AGENTS.md`, tools,
   histórico. Mexer na ordem das tools ou no system prompt entre chamadas = recomeçar o prefill.
2. **Cache semântico (por similaridade da última mensagem) não serve para agente.** A chave seria a
   mesma para 10–49 passos consecutivos do mesmo loop — serviria resposta velha e quebraria o
   trabalho. Serve para FAQ/docs de corpus fixo, não para isso.
3. **Prompt aberto é o que mata modelo local.** Um modelo local não "explora e resolve": ele explora
   *para sempre*. O prompt precisa ser **plano** (tarefas numeradas, arquivos, critérios). Na mesma
   tarefa com prompt aberto: 1º edit no turno 40, 1 edição, 0 commits. Com plano: 1º edit no turno
   3, 13 edições, 1 commit, completa em 3,2 min.
4. **`limit.output` é o teto de *uma resposta* — e corta trabalho bom no meio.** O planejador escreve
   o SPEC dentro da resposta: com 8192 ele parava no meio do arquivo. Subimos para 32768 e sumiu. No
   perfil de contexto longo (256k) aconteceu o mesmo com o agente normal: `Output token limit reached`
   **não** é falta de contexto nem compactação, é `limit.output`. E o OpenCode v2 **não** continua
   sozinho uma resposta cortada (o "continue" é seu). Usamos 32768 em `strata-flash-next`,
   `strata-flash-next-iq3xxs`, `strata-swift-flash-next*` e no 256k; os demais ficam em 8192.

## 5.7 MCPs: o vazamento que culpava o modelo

MCP remoto lento travava o `opencode run` por ~10 min na inicialização (timeout de SSE do servidor
remoto) — e o watchdog do harness atribuía a culpa ao **modelo**. E com o MCP de benchmark ligado
(31 tools a mais), a sessão ficava lenta e poluída.

Isolamento que usamos em bateria: uma cópia da config **com todos os MCPs desligados**, apontada por
`XDG_CONFIG_HOME`, com o resto em symlinks (para não divergir do config real):

```bash
XDG_CONFIG_HOME=/tmp/xdg-isolado opencode run --standalone --auto --model ... "..."
```

Detalhe importante: **`opencode.json` de projeto não desliga MCP global.** Só o XDG isola.
