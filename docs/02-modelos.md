# 2. Modelos recomendados e as quantizações que testamos

Dois eixos decidem tudo: **o que cabe na memória** e **o que fecha uma tarefa de agente**. Eles são
independentes — modelo rápido que não fecha tarefa, modelo ótimo que não cabe no PC.

> Legenda: **medido localmente** = rodamos aqui e anotamos. **model card** = número do autor, não
> reproduzimos. As provas citadas (`api-tarefas`, `agente-spring-ai`) estão em
> [docs/08-avaliacao.md](08-avaliacao.md).

---

## 2.1 O principal: Qwen3.8-Flash-Next 125B-A6B (MoE) + motor Strata

O melhor custo/benefício que encontramos para "agente de código local". Um MoE de 125 B com
**10 experts ativos por token** (512 experts × 48 camadas), cabeça MTP e uma tabela de n-gramas/PLE
de ~29 GB. Com o motor certo, ele roda em 16 GB de VRAM + 54 GB de RAM a ~45 t/s.

| Quant (GSQ-RCO, ISTA-DASLab) | Arquivo | Experts na RAM | Cabe em 54 GB? | Veredito local |
|---|---:|---:|---|---|
| `Q2_0` (2,40 bpw) | 66,4 GB | ~34 GB | sim | opção mais rápida; task avg 89,07 (model card) |
| **`IQ2_XS` (2,50 bpw)** | 68,0 GB | **~35,5 GB** | **sim ← usamos** | **APROVADO** — melhor ponto entre velocidade e qualidade aqui |
| `IQ3_XXS` (3,00 bpw) | 75,8 GB | ~43 GB | no limite | perfil criado, em teste; sem pack pronto = não recomendo ainda |
| `IQ3_S` (3,50 bpw) | ~83 GB | ~50 GB | **não** | precisa ≥ 64 GB de RAM |

*O arquivo é grande porque o shard 2 (≈28,8 GB = `per_layer_token_embd.weight`, a tabela de n-gramas)
é **idêntico** entre as quants — dá para economizar 28,8 GB de download usando hard link:
baixe a segunda quant com `ln` do shard 2 em vez de baixa-lo de novo (mesmo SHA-256, conferido).*

**Regra prática do Strata (o que a gente aplica):** `RAM ≥ experts_em_CPU + ~10 GB`. Com 54 GB, o
teto é IQ2_XS. Com 64 GB, IQ3_XXS/IQ3_S entram no jogo — essa é literalmente a única razão para
fazer upgrade de RAM nesta stack.

**Versão para 32 GB de RAM:** existe `ISTA-DASLab/Qwen3.8-Flash-Next-GSQ-RCO-Coder-GGUF` — mesmo
modelo com **50% dos experts podados** (≈1,89 bpw efetivo). Os autores medem 91% do SWE-bench
Verified do modelo cheio. Fora de código (e texto CJK) é mais fraco: para uso geral, fique com
Q2_0/IQ2_XS/IQ3_S, que mantêm todos os experts.

**Por que Strata e não llama.cpp (no mesmo hardware, medido):**

| | decode | prefill | carga |
|---|---:|---:|---:|
| llama.cpp ROCm + IQ2_XS | ~15–18 t/s | ~450–650 t/s | lento, consome RAM |
| **Strata + IQ2_XS** | **~45 t/s** | **~1.100–1.250 t/s** | ~35 s, ~46–50 GB de RAM |

O Strata aprende **quais experts o seu corpus realmente usa** e mantém esses na VRAM (cache de
experts com hit rate medido de 82–88%). O preço: o motor só serve para essa família de modelos.

**Resultados como executor** (mesma prova, mesmos testes ocultos):

| Execução | api-tarefas | agente-spring-ai (22 testes ocultos) | veredito |
|---|---|---|---|
| manual | ✅ 168 s 4/4 | ✅ ~690 s 8/8, 22/22 | APROVADO |
| automática 1 | ✅ 150 s | ✅ 601 s, 98 tools, 0 erros | APROVADO |
| automática 2 | ✅ 170 s | ❌ loop em 45 s | PARCIAL |

2/3 aprovações. O modo de falha que sobrou: comando com **saída vazia** (`gradlew build -q`) não é
lido como sucesso, então o modelo repete. Mitigação no prompt, não no modelo.

**E como planejador:** é a primeira vez que uma IA local planejou uma feature ponta a ponta aqui.
`planejar-tarefas` 8/8; `planejar-agente` (feature grande em Java sem quebrar compatibilidade)
19/30 no primeiro run — a única falha foi **uma linha de decisão no plano**; corrigida, 30/30.
Fluxo adotado: *Strata planeja → um modelo de nuvem revisa → Strata executa*.

---

## 2.2 Reservas que cabem só na VRAM (densos 27–30B)

Para quando a RAM precisa estar livre (jogo, VM, edição) ou quando você quer resposta em 1 s.

| Modelo | Quant | ctx | Velocidade medida | Veredito local |
|---|---|---:|---|---|
| **Qwen3.8-27B** (ISTA GSQ-RCO, `-mtp`) | **IQ3_S** 11,8 GB | 128k (KV q4_0) | 62–66 t/s · 15,4 GB de VRAM | **APROVADO** (api 145 s, spring-ai 706 s 8/8; um run com 1 falha de aceite → variância) |
| Swift 1.5 Qwen3.8-27B (`ukisai/…`) | IQ3_S-mtp | 128k | 64,5 t/s · 15,4 GB | **APROVADO** (145 s / 706 s) — no nível do gsq-s, deixa ~0,9 GB de folga |
| Muse Glimmer 30B (unsloth) | UD-IQ3_M 13,2 GB | 131k (KV q8_0) | 36–38 t/s · 14,4 GB | **APROVADO só em thinking** (330 s / 1336 s) — correto e ~2× mais lento |
| Qwen3.6-35B-A3B (unsloth, +mmproj) | UD-Q4_K_XL | 131k | 47 t/s · 13,1 GB (+1,2 GB GTT) | **PARCIAL** (api ✅ 290 s; spring-ai 8/8 tarefas, aceite 17/22) |
| Devstral Small 2 24B (unsloth) | UD-Q3_K_XL | 131k | — | baixado, ainda não avaliado |
| Qwen3-Coder-Next 80B-A3B (unsloth) | UD-IQ4_XS | 131k | 24,6 → **30,0 t/s** com cache de experts | em teste |
| Mistral Small 4 119B-A6B (unsloth) | UD-IQ3_XXS | 131k | — | em teste |
| Swift 1.5 Flash-Next 125B (`ukisai/…`) | IQ2_XS no Strata | 32k…256k | 49,2 t/s | promissor (api ✅ 145 s 4/4), **sem veredito formal** ainda |

**Neste grupo, IQ3_S é a resposta para "melhor quant que cabe".** Os números da model card ISTA
para o 27B, com o que cada um custa:

| Quant | bpw | Tamanho | Zero-shot avg | AIME25 | LiveCodeBench v6 |
|---|---:|---:|---:|---:|---:|
| BF16 (referência) | 16,00 | 53,8 GB | 74,34 | 100,00 | 85,71 |
| IQ2_XS | 2,50 | 8,4 GB | 74,54 | 96,67 | 76,57 |
| IQ2_S | 2,75 | 9,3 GB | 75,70 | 100,00 | 82,29 |
| IQ3_XXS | 3,00 | 10,1 GB | 74,81 | 100,00 | 84,57 |
| **IQ3_S** | 3,50 | 11,8 GB | — | — | — (autor: "recomendado, task-lossless") |

Interpretação prática: **em denso de 27B dentro de 16 GB de VRAM, a quant "perdida" para o modelo
cheio é pequena e quase toda ela está em LCB (código difícil)**. IQ3_S é o ponto em que a model card
declarou perda zero; se faltar ~1,5 GB, IQ3_XXS é a descida honesta.

---

## 2.3 Reprovados — o que NÃO tentar (e por quê)

Esta é, de longe, a tabela mais útil deste repo. Todos os abaixo rodaram as **mesmas** provas, com
os mesmos testes ocultos, no mesmo hardware. Quase nenhum falhou por velocidade: falhou por
**comportamento de agente**.

| Modelo | Velocidade | O que aconteceu | Veredito |
|---|---|---|---|
| **gpt-oss-120b** (MXFP4, 63 GB) | **1,3–1,6 t/s** ❌ | 59 GB de pesos > 54 GB de RAM: só ~49 GB residentes, o resto lido do NVMe a cada token. Nenhum erro na tela | REPROVADO (reavaliar só com ≥64 GB) |
| LFM2.5-2.6B | 150 t/s ✅ | usou a ferramenta de executar JS no lugar do shell, 0 commits, quebrou o contrato | REPROVADO |
| Hermes-4-14B | 59 t/s ✅ | **narra o plano e encerra o turno sem chamar nenhuma ferramenta** (0 tool calls, 15–20 s). Tool calling isolado funciona | REPROVADO |
| Ministral-3-8B | 56,5 t/s ✅ | 756 s, 126 tool calls, **0 commits**: não fez o TypeScript rodar e mexeu no `tsconfig`/`package.json` | REPROVADO |
| MiMo-V2.6-9B | 57–59 t/s ✅ | 2/4 tarefas commitadas, depois laço infinito de `pnpm test`/`typecheck` até o timeout de 30 min | REPROVADO |
| gpt-oss-20b | 107 t/s ✅ | effort `medium` **recusou** ("I can't comply") em 10 s; `low` escreveu 10 arquivos sem nunca usar o shell | REPROVADO |
| GLM-4.7-Flash 30B-A3B | 38 t/s ✅ | timeout 30 min, preso na mesma tarefa HTTP — 2ª reprovação seguida | REPROVADO |
| Qwen3-Coder-30B | 35 t/s ✅ | 4/4 commits em 1161 s **mas o aceite falhou** (GET devolvia 404 no lugar de 200) e não marcou as tarefas | REPROVADO |
| Nemotron-Cascade-2 | 55 t/s ✅ | instruct em loop (61× executar JS no lugar do shell); think parou sozinho só com a T1 | REPROVADO |
| Qwen-AgentWorld-35B | — | api ✅ 4/4; no spring-ai escreveu 8192 tokens de análise **sem chamar ferramenta** e estourou a saída. No thinking: 8/8 tarefas mas aceite 0/22 (contrato de tipos errado) | REPROVADO |
| Gemma 4 31B (denso) | **6,9 t/s** ❌ | 61 camadas/14,3 GiB não cabem inteiros na VRAM com ≥3 GB livres (47 camadas a 96k, 60 a 16k) | REPROVADO |
| Gemma-4-26B-A4B QAT | 94–99 t/s ✅ | api ✅ (175 s); spring-ai ❌ instruct em loop 0/8, think 1/8 | PARCIAL (apagado) |
| Laguna-XS-2.1 | 41–43 t/s ✅ | api ✅; spring-ai ❌ timeout 60 min, 0/8 nas duas variantes | PARCIAL (apagado) |
| Ornith-1.5-35B-A3B | 38–40 t/s ✅ | api ✅; spring-ai quase: 8/8 tarefas com 1 falha de aceite (instruct) / timeout com 7/8 commits (think) | PARCIAL (apagado) |

**Padrões que se repetem** (e que valem para qualquer modelo que você tentar depois):

- **Raciocínio sem ação:** o modelo explica o que vai fazer e fecha o turno. Acontece em instruct
  *e* em thinking, e independe da velocidade.
- **Ferramenta errada:** troca shell por exec de JS, ou edit por write. O loop-guard do harness pega,
  mas o modelo reprovou.
- **"Commitar sem funcionar":** 4/4 commits que não passam nos testes. **Nunca aceite veredito sem
  teste oculto.**
- **Refusar com prompt de agente:** gpt-oss recusou só porque o prompt tinha o system-reminder do
  harness. Teste com o seu prompt real.
- **Modelo > RAM é o pior defeito**, porque é silencioso: não dá erro, só fica lento.

---

## 2.4 Tabela de decisão rápida

| Sua máquina | Faça assim |
|---|---|
| 12–16 GB VRAM + 32–48 GB RAM | Flash-Next **Coder** (experts podados) ou Q2_0 no Strata; reserva 27B IQ2_S/IQ3_XXS |
| 16 GB VRAM + 54–64 GB RAM | **este repo inteiro**: IQ2_XS no Strata + gsq-s IQ3_S |
| 16 GB VRAM + 96 GB RAM | IQ3_S ou IQ4 no Strata; MoE grande 100B+ vira opção real |
| 24 GB VRAM (3090/4090/5080) | denso 27B em Q8_0 na VRAM + MTP n-max 2–4; Flash-Next IQ3_XXS |
| 32 GB VRAM (5090) | dá para manter o MoE quase todo na GPU; aí `--n-cpu-moe`/`--fit` mudam de figura — os números deste repo **não** se aplicam direto |
| Só CPU / 8 GB VRAM | esta stack não é para você; fique em denso ≤14B Q8 e não espere agente multi-arquivo |

Sobre **MoE com experts na RAM** (o caso geral, não só o Flash-Next): o `tg` empata em ~12–18 t/s
independentemente do tamanho do modelo — o gargalo é a **banda da RAM**, não o modelo. Consequência
contra-intuitiva e medida aqui: **quant maior = mais qualidade com a MESMA VRAM**, quase de graça.
É por isso que deixamos `qwen3-coder-next` em IQ4_XS e o `mistral-small-4` em IQ3_XXS.

---

## 2.5 De onde vêm os pesos

| Modelo | Repo HuggingFace |
|---|---|
| Qwen3.8-Flash-Next (GSQ-RCO) | `ISTA-DASLab/Qwen3.8-Flash-Next-GSQ-RCO-GGUF` (usamos a revision `ed59f920`) |
| Qwen3.8-Flash-Next Coder (experts podados) | `ISTA-DASLab/Qwen3.8-Flash-Next-GSQ-RCO-Coder-GGUF` |
| Qwen3.8-27B (GSQ-RCO) | `ISTA-DASLab/Qwen3.8-27B-GSQ-RCO-GGUF` |
| Swift 1.5 (fine-tune curto do Flash-Next) | `ukisai/Swift-1.5-Qwen3.8-Flash-Next-GSQ-RCO-GGUF` |
| Swift 1.5 27B | `ukisai/…` (⚠️ confirme o nome exato antes de baixar — é o único que não checei nesta passada) |
| Muse Glimmer 30B · Qwen3.6-35B-A3B · Mistral Small 4 · Qwen3-Coder-Next · Devstral 2 | `unsloth/<modelo>-GGUF` (UD-*) |
| Embedding (RAG) | `Qwen3-Embedding-0.6B` em Q8_0 |

Download:

```bash
pip install -U "huggingface_hub[cli]"
mkdir -p ~/models/qwen3.8-flash-next/IQ2_XS && cd $_
HF_TOKEN=... hf download ISTA-DASLab/Qwen3.8-Flash-Next-GSQ-RCO-GGUF \
  --include "IQ2_XS/*" --local-dir .
# a chave só via variável de ambiente no comando; nunca em arquivo versionado
```

Licenças das weights são próprias (Qwen Community / Apache / Unsloth conforme o caso) — leia a
model card. Este repo não distribui pesos.
