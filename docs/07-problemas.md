# 7. Problemas e soluções (22 defeitos reais que pagamos para aprender)

Se está quebrado, procure aqui antes de reiniciar qualquer coisa. Organizado por categoria; cada linha
é um caso que aconteceu de verdade nesta stack, com o que resolvemos.

## 7.1 VRAM e desktop

| Sintoma | Causa raiz | Solução |
|---|---|---|
| Tela congela 2–10 s sob carga de IA | VRAM livre ≤ 1 GiB: o compositor pede buffer, o `amdgpu` move memória de GPU p/ GTT e falha (`page allocation failure` / `Failed to pin framebuffer -12`) | llama.cpp `--fit on --fit-target 3072`; Strata `--vram-reserve-mib 3072`. Não é ajuste de gosto: com 1 GiB de folga congelava, com 3 GiB parou |
| `--fit on` aborta no load | `-ngl 99` + `--fit` e o modelo não cabe: llama.cpp **aborta**, não reduz contexto | baixar quant/camadas, ou `-ngl` parcial + `--n-cpu-moe` |
| Hangs de vídeo (MES/SDMA timeout, reset falha `-110`) em carga **leve** | undervolt de `-90/-70 mV` no perfil LACT que a placa não sustenta | zerar `voltage_offset` nos perfis; se voltar, subir firmware MES / kernel LTS. (Zeramos e parou) |
| Strata carregado deixa o PC inteiro travado | padrão do motor reservava ~700 MiB de VRAM | `--vram-reserve-mib 3072` (o upstream hoje recomenda o mesmo em Linux desktop) |
| Engine Strata continua rodando depois de "parar" o modelo | SIGTERM matou o Python, não o engine filho → **~14 GB de VRAM órfãos** | wrapper com `setsid` + `trap` que mata o **grupo** ([docs/04](04-strata.md)) |
| Decode cai de 102 para 13 t/s sem nenhum erro | cache de experts maior que a VRAM: a alocação "cabe" em sysmem e só a velocidade denuncia | deixar `--expert-cache auto`, que re-confere depois de escrever os slots |

## 7.2 RAM, swap e armazenamento

| Sintoma | Causa raiz | Solução |
|---|---|---|
| Modelo a **1,3–1,6 t/s** "funcionando" | 59 GB de pesos em 54 GB de RAM: page eviction para o NVMe a cada token | regra `RAM ≥ pesos_residentes + folga`. Reavaliar só com mais RAM. E verificar residência com `fincore` — o log não avisa |
| "A quant X é ruidosa/inconsistente" | o arquivo (71 GB) é maior que a RAM; a variação era page cache, não o modelo | medir residência antes de culpar a quant |
| Memória parece saudável mas trava no load | zram + `vm.swappiness=150` dá **falso positivo** de swap funcionando | olhar `VmSwap` do processo e o PSI, não o `free` |
| `mlock` falha silenciosa (load lento) | `ulimit -l` padrão = 8 MiB | `ulimit -l unlimited` no `ExecStart` (o serviço tem isso) |
| `/tmp` sumiu e perdi os scripts da fila | `/tmp` é apagado no reboot | scripts de longo prazo sobrevivem em `~`, nunca no scratchpad |
| Pack corrompido (checksum) no meio de download grande | incidente transitório de `btrfs csum` | `btrfs scrub start` (precisa de sudo); reconstruir o pack |
| Download de 63 GB recomeçou do zero | troquei o token de auth **no meio** do download | não mexer em credencial com download rodando; conferir parciais órfãos |

## 7.3 Motor (llama.cpp / Strata)

| Sintoma | Causa raiz | Solução |
|---|---|---|
| O mesmo comando repetido 46×, 140× em loop | **greedy**: os JSONs do Strata não tinham bloco `sampling` e o cliente não manda temperatura (`serve/server.py` caía em temp 0) | bloco `sampling` oficial nos JSONs → **0 loops em 288 chamadas**. Regra: todo motor novo precisa de sampling explícito |
| Reasoning "engasga" e repete frases | temp baixa (< 0.5) em modelo de reasoning | usar o valor oficial do **modo**, não do modelo |
| Raciocínio cortado no meio da resposta | teto de tokens atingido | `--reasoning-budget-message` para o modelo fechar sozinho; subir o budget |
| `--fit` aborta com `INDEXER_TOPK` na CPU | fork com MTP + `--fit`: o índice cai em CPU e o build não sabe | fixar `-ngl 999 --n-cpu-moe N` nesse fork (`--fit off`) |
| HTTP 500 no Ministral | o template oficial **exige** alternância user/assistant | `--chat-template-file` com uma versão relaxada (`.jinja` próprio) |
| Seletor de raciocínio não faz nada | `--reasoning on` no cmd fixa `enable_thinking=true` e ignora o `reasoning_effort`; ou `--reasoning off` no perfil instruct | deixar o template decidir: tirar o flag fixo e validar o Jinja |
| Template custom "engolia" effort sem erro | o template embutido no GGUF tem `raise_exception` para effort inválido; o nosso tinha perdido o ramo `medium` | validar o template renderizando com Jinja2 e restaurar a validação |
| "O modelo Q4_K_XL só faz 1,7 t/s" | medi o LM Studio, não o llama.cpp (mesmo arquivo: **34 t/s**) | conferir sempre o backend/executor antes de comparar números |

## 7.4 llama-swap

| Sintoma | Causa raiz | Solução |
|---|---|---|
| Modelo demora e o llama-swap mata no meio do load | `healthCheckTimeout` padrão curto p/ MoE grande | `healthCheckTimeout: 600` no topo do config |
| O modelo troca sozinho no meio de um teste | **os próprios testes do aceite chamavam `:8082`** → cada teste pedia um modelo diferente e o swap derrubava a sessão em uso | apontar os testes para o backend direto, ou fixar `ttl: -1` durante a bateria. Diagnóstico: cruzar o journal (`Health`/`Unload`) com o horário do `gradlew test` |
| Editar config derruba stream em uso | `-watch-config` recarrega na hora | não editar durante benchmark; se editar, saber que a rodada é perdida |
| Perfil "proxy" para um servidor já rodando | llama-swap não tem perfil proxy-only | `cmd: sleep infinity` + `checkEndpoint: none` (funciona, mas é gambiarra — o `cmdStop` é o caminho decente para containers) |
| `/v1/models` não bate com o config | cache/UI enganosa | `curl localhost:8082/v1/models` cru |

## 7.5 Agente (OpenCode)

| Sintoma | Causa raiz | Solução |
|---|---|---|
| Todos os modelos locais somem e dá `Model unavailable` | no v2 `variants` é **objeto**; array do v1 + `additionalProperties:false` descarta o **provider inteiro** | array → objeto + `opencode reload` (**restart não resolve**). Diagnóstico: `opencode api GET /api/config` e o log "configuration normalization diagnostic kind=invalid" |
| `opencode run` travado ~9,5 min sem fazer nada | MCP remoto (SSE) com timeout bloqueando a inicialização | rodar bateria com `XDG_CONFIG_HOME` isolado e **todos os MCPs off** (config de projeto não desliga MCP global!) |
| Sessão lenta/poluída | 31 tools de um MCP de benchmark no contexto | desligar MCP que não é do caso; 93% das chamadas são meio-de-loop, cada tool custa caro |
| TUI congela depois de mexer no config | `opencode service restart` com TUI aberta | fechar a TUI antes de reiniciar o serviço |
| `opencode -m` não existe | na TUI v2 não há `-m` | `opencode.json` de projeto + `.git/info/exclude`, ou `/models` |
| `exceed_context_size_error` no meio da sessão | llama.cpp **não tem** janela deslizante: estourou, erro | `limit.context` = `-c` real; o OpenCode compacta antes (a ~118k do nosso 128k), mas o erro ainda aparece se o cliente não compactar |
| `opencode mcp list` vazio | bug do v2 (mente) | conferir o `opencode.json` e `/api/config`, não o `mcp list` |
| Integração ACP na IDE: "no session table" | o registry da JetBrains fixa o **OpenCode v1.18** contra o `opencode.db` do **v2** (schema `session_v2`) | registrar o binário v2 como agente custom (`acp.json`) e reiniciar a IDE |

## 7.6 Método de medição (os erros que quase nos fizeram decidir errado)

| Quase conclusão errada | O que estava errado |
|---|---|
| "O n-gram-mod deixa o agente 70% mais rápido" | rápido no microbench de reescrita de arquivo; **0%** no agente real (115 passos × 6,7 s escrevendo código novo). Nunca promova otimização sem medir no workload real |
| "Esse modelo é melhor: 6/6 no teste TypeScript" | `error_loop` em Java (0/8). Dois testes, dois vereditos — a escada precisa ter a prova difícil |
| "HumanEval diz se serve para agente" | não mede o eixo multi-arquivo. O que separa é tarefa com tool calls, commits e testes ocultos |
| "O modelo local não fecha tarefa nenhuma" | 60% era o **scaffolding**: prompt aberto vs plano. Com plano, o mesmo modelo fez 13 edições e 1 commit em 3,2 min |
| "A quant IQ3_XXS é instável" | modelo > RAM (page cache). Ver 7.2 |
| "Veredito APROVADO" com 1 run | 2 de 3 runs aprovaram o mesmo modelo. Rodar pelo menos **duas** rodadas antes de promover |

## 7.7 Hardware/SO que não é culpa da stack

| Sintoma | Diagnóstico | Ação |
|---|---|---|
| PC reinicia sozinho sob carga; kernel loga MCE não corrigido no boot seguinte | **MC1 (Instruction Fetch Unit, L1, Poison) no núcleo 6**, mesma assinatura em dois eventos → núcleo instável (provável Curve Optimizer/PBO agressivo) | BIOS: Curve Optimizer do núcleo (ou todos) = 0 e/ou PBO off; validar com `journalctl -b -k \| grep -c "Machine check"` após horas de carga |
| Banda de RAM abaixo do esperado | 4 pentes mistos, canais 24/32 **assimétricos** | parear os pentes (2×32 + 2×16 no nosso plano); medir banda com 2–4 threads (com mais threads o número **cai**) |
| Congelamento em VM/PCIe pass-through | driver/KFD + reclaim de host pages suspendendo filas | variáveis documentadas no upstream do Strata (`HSA_USERPTR_FOR_PAGED_MEM=0`, `GPU_PINNED_MIN_XFER_SIZE=1048576`) — uma por vez, com tempo medido |

> **Regra de ouro de diagnóstico:** nunca atribua ao modelo o que pode ser I/O, memória, config de
> motor ou ferramenta. Todas as nossas "reprovações de modelo" que depois se explicaram por outro
> motivo (greedy do motor, MCP travando, page eviction) estavam **erradas** — e o custo foi dias.
