# local-ai-stack

Stack de IA local para **programar com agente** numa máquina de placa de vídeo de consumo —
documentada, com as configs que usamos no dia a dia.

Não é um projeto teórico: é o registro do que montamos, medimos e quebramos entre 2026-10-01 e
2026-10-07 numa máquina **AMD RX 9070 XT (16 GB) + 54 GB de RAM + Ryzen 9 5950X**, rodando
**Qwen3.8-Flash-Next 125B-A6B** como executor e planejador de código, com llama.cpp, Strata,
llama-swap e OpenCode.

Se você tem um rig parecido (12–16 GB de VRAM, 48–64 GB de RAM, GPU AMD ou NVIDIA) e quer
saber **o que configurar, qual quant usar, com quais flags e o que não costuma funcionar**, este
repo é para você.

**Para montar do zero, comece por [SETUP.md](SETUP.md)** — passo a passo com os comandos conferidos.
Para entender e decidir, os docs abaixo.

---

## TL;DR — o que funciona

| Papel | Configuração | Por quê |
|---|---|---|
| **Executor + planejador (principal)** | `Qwen3.8-Flash-Next 125B-A6B` **IQ2_XS** no motor **Strata** (HIP/ROCm), ctx 131072 | ~45 t/s decode / ~1.100 t/s prefill (≈3× o llama.cpp no mesmo hardware). Fecha as duas provas de agente sem loop |
| Reserva leve (só VRAM) | `Qwen3.8-27B` **GSQ IQ3_S-mtp** no llama.cpp **Vulkan**, ctx 128000, KV q4_0 | 62–66 t/s, cabe inteiro na placa; para quando a RAM precisa estar livre |
| Reserva alternativa | `Swift 1.5 27B IQ3_S-mtp` (Vulkan) · `Muse Glimmer 30B UD-IQ3_M` (thinking) | mesmo nível do gsq-s / aprovado porém ~2× mais lento |
| Contexto longo | `strata-flash-next-256k` (ctx 262144) | needle 3/3 em 32k/128k/262k; prefere RAM livre, use com `ttl` |
| Duas sessões grandes ao mesmo tempo | `"parallel": 2` no JSON do Strata (perfil 256k) | ~0,95 GiB de VRAM + ~3 GiB de RAM pinada por slot; medido com duas conversas de ~145k e ~198k — [docs/04 §4.6](docs/04-strata.md#46-rodar-duas-conversas-grandes-em-paralelo-batching) |

**As regras que evitam a maior parte dos problemas que tivemos:**

1. **Deixe ≥ 2–3 GB de VRAM livres.** Com ≤ 1 GB o kernel loga `page allocation failure` no
   `amdgpu` e a tela congela. No llama.cpp: `--fit on --fit-target 3072`. No Strata:
   `--vram-reserve-mib 3072`.
2. **Um modelo grande por vez.** O principal ocupa ~46–50 GB de RAM; o `ttl` do llama-swap devolve a RAM
   ociosa.
3. **Sampling explícito.** Sem o bloco `sampling`, o Strata fica em *greedy* quando o cliente não manda
   temperatura — e entramos em loop de repetição (46× e 140× o mesmo comando) antes de ver isso.
4. **Modelo maior que a RAM fica lento sem avisar.** 59 GB de pesos em 54 GB de RAM deram **1,3 t/s**
   (page eviction para o NVMe a cada token), sem nenhum erro na tela.
5. **Velocidade de benchmark ≠ velocidade de agente.** Um truque de speculative decoding deu +70% t/s
   no microbench e 0% no trabalho real.

---

## Documentos

| | |
|---|---|
| [SETUP.md](SETUP.md) | **Montar a stack do zero**: checar encaixe, baixar pesos, compilar motores, llama-swap, Strata, OpenCode, teste de fumaça por camada, checklist |
| [docs/01-maquina.md](docs/01-maquina.md) | Especificação do rig, orçamento de VRAM/RAM, como medir o seu, regras de ouro |
| [docs/02-modelos.md](docs/02-modelos.md) | **Modelos recomendados e as quantizações que testamos** (com números), tabela de aprovados/reprovados e por quê |
| [docs/03-llama-swap.md](docs/03-llama-swap.md) | Instalar e configurar o llama-swap: macros, padrão de perfil, `ttl`, saúde, troca de modelo |
| [docs/04-strata.md](docs/04-strata.md) | Motor Strata (o que faz o Flash-Next render 45 t/s em 16 GB): setup, JSON, ajustes necessários, wrapper, **como rodar duas conversas de 256k em paralelo** |
| [docs/05-opencode.md](docs/05-opencode.md) | Usar como agente: provider `openai-compatible`, sincronizar modelos, variantes de raciocínio, limites |
| [docs/06-tuning.md](docs/06-tuning.md) | Sampling oficial por família, MTP/speculative, KV e contexto máximo, `--reasoning-budget`, armadilhas |
| [docs/07-problemas.md](docs/07-problemas.md) | **22 problemas reais que tivemos e a solução de cada um** (comece aqui se está quebrado) |
| [docs/08-avaliacao.md](docs/08-avaliacao.md) | Como testar se um modelo novo serve como executor (escada de provas + veredito) — e como rodar os [fixtures](fixtures/README.md) publicados |
| [templates/README.md](templates/README.md) | Os `.jinja` que ajustamos (effort validado, thinking desligado, alternância relaxada) e como validar sem subir modelo |

## Configurações de referência

| Arquivo | O quê |
|---|---|
| [config/llama-swap.yaml](config/llama-swap.yaml) | Nosso config completo (macros + 17 perfis testados), comentado |
| [config/strata/](config/strata/) | `strata-iq2_xs.json` (+ a variante 256k), os `.shared-settings.json` e o wrapper `strata-swap.sh` |
| [config/opencode-provider.json](config/opencode-provider.json) | Bloco `provider.llama-cpp` para o `~/.config/opencode/opencode.json` |
| [config/systemd/](config/systemd/) | Units de usuário: llama-swap, sync de modelos (service + timer) |
| [scripts/ia.sh](scripts/ia.sh) | `ia on \| off \| status` — liga/desliga a stack (libera VRAM/RAM para jogar) |
| [scripts/sync-opencode-models.py](scripts/sync-opencode-models.py) | Espelha os perfis do llama-swap no OpenCode preservando limites por modelo |
| [scripts/fit-check.sh](scripts/fit-check.sh) | Oráculo de VRAM por GGUF/ctx/backend antes de subir o servidor |
| [fixtures/](fixtures/README.md) | **Os 4 projetos de teste** (2 de execução, 2 de planejamento), com o aceite oculto, o prompt e `bootstrap.sh`/`verify.sh` |
| [templates/](templates/README.md) | Os `.jinja` que ajustamos (reasoning effort validado, thinking desligado, alternância relaxada) + `check_render.py` |

## Começando em 6 passos

```bash
# 0. ajuste os caminhos do config de referência para a sua máquina (procure por /home/YOU)
grep -rn '/home/YOU' config/ | head

# 1. motores
git clone https://github.com/ggml-org/llama.cpp ~/llama-engines/llama.cpp
cd ~/llama-engines/llama.cpp
cmake -B build-vulkan -DGGML_VULKAN=ON -DGGML_NATIVE=ON -DCMAKE_BUILD_TYPE=Release && cmake --build build-vulkan -j"$(nproc)"
# (AMD com ROCm instalado: repita com -DGGML_HIP=ON -DAMDGPU_TARGETS=gfx1201 -DGGML_HIP_ROCWMMA_FATTN=ON)

# 2. llama-swap (binário único)
#    https://github.com/mostlygeek/llama-swap  -> solte o binário em ~/llama-engines/llama-swap/
cp config/llama-swap.yaml ~/llama-engines/llama-swap/config.yaml
~/llama-engines/llama-swap/llama-swap -config ~/llama-engines/llama-swap/config.yaml -validate

# 3. Strata (motor do Flash-Next) + modelos GGUF — docs/04 e docs/02
# 4. unidades systemd de usuário + scripts — docs/03 e docs/05
systemctl --user enable --now llama-swap.service
# 5. valide: o modelo principal responde?
curl -s http://127.0.0.1:8082/v1/models
```

Detalhes de cada passo, com as flags reais e os números medidos, estão nos documentos acima.

## O que este repo não é

- Não é um fork nem um wrapper dos motores: llama.cpp, Strata, llama-swap e OpenCode são projetos
  de terceiros (links em cada doc). Nada aqui foi compilado ou escrito por nós além das configs.
- Não inclui o nosso harness `local-exec` (eval/run/verify com watchdog) nem as skills de processo:
  ficam fora porque dependem do nosso fluxo interno. Em [docs/08](docs/08-avaliacao.md) está a receita
  equivalente, e em [fixtures/](fixtures/README.md) os 4 projetos de teste com `bootstrap.sh` e
  `verify.sh`.
- Não é recomendação de compra. Os números são do nosso hardware; o mesmo GGUF em outra placa se
  comporta de outro jeito (em [docs/01](docs/01-maquina.md) dizemos como medir o seu caso).

## Licença e atribuição

Código/configs deste repositório: **MIT**. Os pesos têm licenças próprias (Qwen, ISTA-DASLab,
UkisAI, Unsloth) e os motores são MIT/llama.cpp, Strata MIT, llama-swap MIT — ver cada doc.
Nada aqui distribui modelos.
