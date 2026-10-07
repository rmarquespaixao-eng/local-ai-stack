# 6. Tuning: sampling, speculative decoding, KV e contexto

A parte que mais gera "por que esse modelo está pior que no YouTube?". Quase sempre não é o modelo:
é sampling, folga de VRAM, ou o contexto que você acha que tem.

## 6.1 Sampling — valores oficiais por família (o que usamos)

Passar **todos** explícitos. O default do llama.cpp fora do GGUF é `min-p 0.05` /
`repeat-penalty 1.1`, que não é o valor de nenhum fornecedor.

| Família / modo | temp | top-p | top-k | min-p | repeat / presence |
|---|---:|---:|---:|---:|---|
| Gemma 4 · Muse Glimmer (todo uso) | 1.0 | 0.95 | 64 | 0.0 | 1.0 / 0 |
| **Qwen3.8 / Bonsai — instruct** (reasoning off) | **0.7** | **0.80** | 20 | 0.0 | 1.0 / **presence 1.5** |
| **Qwen3.8 / Flash-Next — thinking** | **1.0** | **0.95** | 20 | **0.05** | 1.0 / 0 |
| Qwen3.8 unificado (seletor liga/desliga) | 1.0 | 0.95 | 20 | 0.05 | 1.0 / presence 1.5 |
| Strata (motor do Flash-Next) thinking | 0.6 | 0.95 | 20 | 0.0 | 0 / presence 1.5 |
| Strata instruct | 0.7 | 0.80 | 20 | 0.0 | 0 / presence 1.5 |
| Laguna S 2.1 (thinking, receita vLLM) | 0.7 | 0.95 | 20 | 0.0 | — / 0 (**não** use min-p com spec) |
| Devstral Small 2 | 0.15 | — | — | 0.01 | — |
| Qwen3-Coder-Next | 1.0 | 0.95 | 40 | 0.0 | 1.0 / 0 |
| Qwen3-Coder-30B | 0.7 | 0.80 | 20 | 0.0 | 1.05 / 0 |
| gpt-oss (MXFP4 nativo) | 1.0 | 1.0 | 0 | 0.0 | — / — |
| Ornith / Qwen3.6 unificados | 0.6 | 0.95 | 20 | 0.0 | 1.0 / presence 1.5 |

Regras que explicam a tabela:

- **`top-k 20` é truncation "eval-certified"** (poolside) e o padrão Qwen/Bonsai. Gemma/Muse usam 64.
- **`presence-penalty 1.5` é o mecanismo anti-repetição oficial** do modo instruct Qwen/Bonsai (a
  model card diz explicitamente "reduce endless repetition"). Alto demais mistura idiomas.
- **Modelo de reasoning com temp baixa (< 0.5) entra em loop no thinking.** Use o valor do modo.
- **Mudou temp, mudou a saída.** Só mexa com um motivo e com A/B.
- Auditoria que fizemos (2026-10-06): os clientes que usamos **não mandam sampling** — quem
  decide é o perfil. Se o perfil não tem, o motor decide por você (e no Strata isso era greedy).

## 6.2 MTP / speculative decoding — onde a velocidade mora

O llama.cpp usa a cabeça MTP embutida no GGUF (`blk.*.nextn.*`) para propor N tokens e verificar com
o modelo principal em uma passada. Flag base:

```
--spec-type draft-mtp --spec-draft-n-max 2 --parallel 1
```

As 7 regras da comunidade (sudoingX/qwen38-mtp, 53 configs coletadas) que nos salvaram de números
errados:

1. `n-max` depende de GPU + workload. Varra 2–4; não adote cego.
2. **`--spec-draft-p-min` ajuda GPU faminta de banda e atrapalha GPU rápida.** "Sweep it, don't
   adopt it." Use 0.60–0.75 em rig pobre de banda.
3. O ganho escala com o tamanho da geração; em gerações curtas você pode pagar mais caro.
4. Multi-GPU: arrume `--split-mode` antes de mexer em spec.
5. **Spec é single-stream**: a vantagem acaba em `--parallel 4`. Meça sempre em `--parallel 1`.
6. **Recompile o llama.cpp antes de tunar** (melhora semanal) e re-derive por classe de card.
7. Desktop compartilhado corta o ganho pela metade — meça headless ou confira `mem_info_gtt_used`.

Para RX 9070 XT 16 GB (RDNA4, Vulkan, não faminta de banda), o ótimo medido:

| Knob | Valor | Motivo |
|---|---|---|
| `--spec-draft-n-max` | **2** | varremos: n=4 derruba (59,8 → 50 t/s) |
| `--spec-draft-p-min` | **0** (sem gate) | regra 2; **0.6 é o fallback** só se a aceitação cair < 0.5 (contexto longo de agente) |
| `--spec-draft-n-min` | 0 | não corta cedo |
| KV do draft | `-ctkd q8_0 -ctvd q8_0` | poupa VRAM sem custo de qualidade |

Aceitação medida no nosso gsq-s: ~0,64–0,68 com n-max 2. Como ler: `--verbose` no log imprime
"accepted X / drafts Y".

Ganhos reais medidos aqui: 17,7 → **22,9 t/s** (+29%) no Flash-Next com MTP em fork; 47 → 59,8 t/s no
27B. E dois resultados negativos que também valem:

- **`ngram-mod` deu +70% no microbench e 0% no agente real.** No bench era reescrever arquivo
  (caso do n-gram); no agente, 115 passos × 6,7 s escrevendo código novo por diff — o n-gram não
  acerta nada. **Não aplicamos.**
- **`-ub 2048`**: nenhum ganho de prefill no server (~720 t/s) e +0,3–0,6 GiB de VRAM. Descartado.

## 6.3 Contexto e KV: como achar o máximo real

Conta por modelo: `camadas × (kv_heads × head_dim × 2 × bytes_por_token)` mais os estados de SSM
nos híbridos. Não decore — use o oráculo:

```bash
llama-fit-params --model arquivo.gguf -fitp on -c 131072 -ctk q8_0 -ngl 99 --backend-rocm
# imprime: pesos | KV | compute por backend, sem inferir. Calibramos ±2% contra rocm-smi real.
```

Armadilhas medidas (cada uma queimou tempo aqui):

| Armadilha | Realidade |
|---|---|
| `--fit on` vai reduzir meu contexto se não couber | **Não.** Com `-c` explícito, se não couber o llama.cpp **aborta**. O `--fit` só escolhe camadas, e só reduz ctx com `-c 0` |
| `--ctx-checkpoints N` custa VRAM | **Não custa** (medido 0 vs 32: mesma VRAM). São snapshots da KV em **RAM** do host, que servem para rollback de cauda em modelos híbridos (SWA/SSM). Sem eles, reenviar um prefixo cacheado reprocessa o contexto todo: 0,55 s → 2,39 s a ~1,7k tokens (a 100k+ são minutos) |
| mmproj não pesa nada | Pesa **~1,7 GiB** e **não** é modelado pelo fit-params. Some na mão em todo perfil multimodal |
| ctx maior é de graça | Custa velocidade: 47 → 17,7 t/s entre 64k e 262k no mesmo GGUF. O custo de contexto cheio é **latência**, não erro |
| KV q8 é obrigatório para qualidade | q8 ≈ lossless; q4 ≈ −1–5% nas nossas medições. Em 16 GB, os modelos grandes **ficam em q4 por teto de VRAM**, não por escolha |
| `-ctk q8_0` a 128k cabe | No gsq-s não: 24,7 GB → transbordo p/ GTT → 24 t/s. q8 segura até ~96k; a 128k use q4 |
| o modelo é a arquitetura que o nome diz | Leia o GGUF: `general.architecture = qwen4exp`, 48 camadas, 512 experts/10 ativos, 3/4 das camadas são SSM (`full_attention_interval 4`). `gguf-py` resolve: `python -c "from gguf import GGUFReader; ..."` |

## 6.4 Reasoning (thinking) — como não perder o dia

- Default do `--reasoning-budget` é **−1 (sem teto)**. Modelos Qwen3.5/3.6 chegam a **100k+ tokens**
  de raciocínio solto. Sem teto, uma pergunta simples come a janela inteira.
- **Qwen (oficial):** nunca abaixo de 1024.
- **Receita para agente:** `--reasoning-budget 4096` **+**
  `--reasoning-budget-message "I have thought long enough -- let me produce the final answer now."`
  — o teto vira segurança e a mensagem faz o modelo **fechar o raciocínio sozinho**, sem corte seco.
- O knob que mais corta tempo é o **effort** (`reasoning_effort` low/medium/xhigh) — mas ele depende
  do **template** tratar a variável. O template oficial embutido no GGUF declara
  `not in ('xhigh','medium','low')` + `raise_exception`; um template `.jinja` seu precisa manter essa
  validação ou o effort é ignorado em silêncio.
- `--reasoning-preserve` (default ON) mantém o raciocínio dos turnos anteriores — importante para
  agente multi-turno.
- **Thinking serve para planejar, não para executar.** Medido: ~45% mais lento e nenhum ganho de
  qualidade na prova de execução (mesmo GGUF, mesma prova).

## 6.5 ROCm vs Vulkan (AMD)

| Situação | Backend | Evidência |
|---|---|---|
| MoE com experts na RAM | **ROCm/HIP** | llama-bench IQ2_XS: pp16384 **695–742** t/s (ROCm) vs 346–570 (Vulkan); no server 64k: 445–450 vs 320–393. Decode quase igual (−2 a −6%) |
| Denso que cabe inteiro na VRAM | Vulkan | 62–66 t/s no gsq-s; simples, sem ROCm instalado |
| Chat rápido genérico / multimodal | tanto faz | diferença some para o usuário |

Prefill é onde o ROCm ganha, e prefill é o que o agente faz **o tempo todo** (reenviar contexto a
cada passo). Por isso o principal ficou em HIP.

Se for compilar com ROCm para RDNA4, as flags que funcionaram:

```bash
cmake -B build-rocm -DGGML_HIP=ON -DAMDGPU_TARGETS=gfx1201 -DGGML_HIP_ROCWMMA_FATTN=ON \
      -DGGML_NATIVE=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build-rocm -j32
```

E o fork/PR do cache de experts (`--moe-cache-mib`, PR ggml#29887) deu +22% t/s no coder-next e
+6,5% no agentworld em 16 GB, sem pinagem e com o `--fit` descontando o cache. Aplicamos em 14
perfis MoE com ~10% dos experts no cache.

## 6.6 Ordem sensata para tunar um modelo novo

1. `llama-fit-params` → escolha quant e ctx que cabem com folga ≥ 3 GiB de VRAM.
2. Carga real + `rocm-smi`/sysfs no **pico** (load + geração), headless se possível.
3. Sampling = valores oficiais da model card (seção 6.1).
4. Reasoning: budget 4096 + message, effort por caso de uso.
5. MTP: n-max 2, sem p-min; meça aceitação; só então teste p-min 0.6.
6. Backend: meça os dois em prefill, que é o que o agente usa.
7. **Só depois** rode uma prova de agente ([docs/08](08-avaliacao.md)) — velocidade de texto não
   prevê nada do eixo multi-arquivo.
