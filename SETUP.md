# SETUP — do zero ao primeiro agente local

Passo a passo para montar esta stack numa máquina Linux (AMD ou NVIDIA) com **12–16 GB de VRAM e
48–64 GB de RAM**. Cada passo diz o comando, o que você deve ver se deu certo, e o link do detalhe.

Tempo real da nossa máquina: ~3 h (1 h de download de pesos + 20 min de compilação do engine + o
restante). Disco: ~140 GB (70 GB por quant do Flash-Next, ~6 GB do MTP, ~10 GB do ROCm quando o setup
baixa, 12 GB do 27B).

> Se você tem menos VRAM/RAM que isso, leia a tabela de decisão em
> [docs/02 §2.4](docs/02-modelos.md) **antes** de baixar qualquer coisa — ela diz qual quant e qual
> motor cabem, e evita o erro mais caro (modelo maior que a RAM: lento e silencioso).

---

## 0. Checar o encaixe (10 minutos, sem baixar nada)

```bash
# GPU, VRAM total e livre
cat /sys/class/drm/card*/device/mem_info_vram_total /sys/class/drm/card*/device/mem_info_vram_used
free -g | head -2                 # RAM utilizável
grep -m1 -o avx2 /proc/cpuinfo                     # o engine pronto do Strata exige AVX2
df -h ~ /mnt/* 2>/dev/null | awk 'NR>1 && $4 != "Use" {print $6, $4 " livres"}'   # precisa de 140 GB

# driver AMD: basta o amdgpu do kernel (não instale ROCm à mão, o setup do Strata resolve isso)
ls /dev/kfd /dev/dri
```

O que anotar e usar o tempo inteiro:

```
MODELOS=~/models            # 140 GB livres aqui
ENGINES=~/llama-engines     # builds do llama.cpp + llama-swap
```

> Se `cat /sys/class/drm/card1/...` falhar, descubra o número do card com `ls /sys/class/drm/` — o
> resto do guia assume `card1` para a GPU dedicada.

---

## 1. Criar a estrutura

```bash
mkdir -p ~/models ~/llama-engines ~/llm/templates ~/.config/llama-swap ~/.local/bin
git clone https://github.com/rmarquespaixao-eng/local-ai-stack /tmp/local-ai-stack   # os configs deste repo
```

Todos os arquivos de exemplo usam `/home/YOU`. Troque pelo seu usuário de uma vez:

```bash
cd /tmp/local-ai-stack && grep -rl '/home/YOU' config | xargs sed -i "s|/home/YOU|$HOME|g"
```

---

## 2. Baixar os pesos (deixe para trás, é demorado)

Só dois arquivos são indispensáveis para o principal + a reserva:

```bash
pip install -U "huggingface_hub[cli]"     # dá o `hf` (conferido com huggingface_hub 2.1.1)

# 2.1 veja o tamanho antes de baixar (o --dry-run não gasta banda)
hf download ISTA-DASLab/Qwen3.8-Flash-Next-GSQ-RCO-GGUF --include "IQ2_XS/*" --dry-run
#   IQ2_XS/…-00001-of-00002.gguf   39.2G   ← experts + pesos densos
#   IQ2_XS/…-00002-of-00002.gguf   28.8G   ← tabela PLE (idêntica em todas as quants)
#   total 68.0G

mkdir -p ~/models/qwen3.8-flash-next/IQ2_XS && cd $_
hf download ISTA-DASLab/Qwen3.8-Flash-Next-GSQ-RCO-GGUF --include "IQ2_XS/*" --local-dir .

# 2.2 Qwen3.8-27B IQ3_S com cabeça MTP (12,1 GB) — reserva que cabe só na VRAM
mkdir -p ~/models/qwen3.8-27b && cd $_
hf download ISTA-DASLab/Qwen3.8-27B-GSQ-RCO-GGUF \
  --include "Qwen3.8-27B-GSQ-RCO-IQ3_S-mtp.gguf" --local-dir .
```

**Truque que economiza 28,8 GB por quant extra:** o shard 2 é a tabela PLE e é o mesmo arquivo em
todas as quants (o upstream confirma e conferi os tamanhos). Baixe só o shard 1 da quant nova e
hardlinke o resto:

```bash
mkdir -p ~/models/qwen3.8-flash-next/IQ3_XXS && cd $_
hf download ISTA-DASLab/Qwen3.8-Flash-Next-GSQ-RCO-GGUF --include "IQ3_XXS/*-00001-of-00002.gguf" --local-dir .
ln ../IQ2_XS/Qwen3.8-Flash-Next-GSQ-RCO-IQ2_XS-00002-of-00002.gguf \
   Qwen3.8-Flash-Next-GSQ-RCO-IQ3_XXS-00002-of-00002.gguf     # mesmo conteúdo, nome do padrão da quant
```

Conferimos os SHA-256 dos dois shards e são iguais; se você estiver em outra revision do repositório,
compare antes de hardlinkar (`sha256sum` de um pedaço já basta para a tabela).

**Para 32 GB de RAM:** a variante Coder tem metade dos experts podados (`IQ1_M/…-00001` = 29,6 GB,
sendo 23 GB de experts; o shard 2 é o mesmo do original):
`ISTA-DASLab/Qwen3.8-Flash-Next-GSQ-RCO-Coder-GGUF`.

> A chave do Hugging Face (se o repo pedir) vai como variável no próprio comando —
> `HF_TOKEN=... hf download ...` — e não em arquivo versionado.

---

## 3. Motores de inferência

### 3.1 llama.cpp (para os densos e os MoE "comuns")

```bash
git clone https://github.com/ggml-org/llama.cpp ~/llama-engines/llama.cpp
cd ~/llama-engines/llama.cpp

# Vulkan: funciona em AMD e NVIDIA sem runtime proprietário
cmake -B build-vulkan -DGGML_VULKAN=ON -DGGML_NATIVE=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build-vulkan -j"$(nproc)"

# ROCm/HIP (AMD): ganha muito em prefill de MoE com experts na RAM
cmake -B build-rocm -DGGML_HIP=ON -DAMDGPU_TARGETS=gfx1201 -DGGML_HIP_ROCWMMA_FATTN=ON \
      -DGGML_NATIVE=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build-rocm -j"$(nproc)"
```

Confira que os binários existem (o `llama-fit-params` é o oráculo de VRAM usado depois):

```bash
ls build-*/bin/llama-server build-*/bin/llama-fit-params
```

### 3.2 Strata (o motor do Flash-Next — é o que dá os ~45 t/s)

```bash
git clone https://github.com/Niko1221/Strata ~/llm/Strata
cd ~/llm/Strata

./setup.sh --check        # 1. só diz se a placa/RAM/disco servem (nada é baixado)

# 2. instala sem ligar o modelo (quem vai ligar é o llama-swap)
./setup.sh --setup --backend hip --family qwen --model IQ2_XS --context 131072 \
           --vram-reserve-mib 3072 --gguf-dir ~/models/qwen3.8-flash-next/IQ2_XS \
           --no-start --no-browser --yes
```

(`--family` aceita `qwen|swift|coder|unsloth`; `--model` aceita `Q2_0|IQ2_XS|IQ3_XXS|IQ3_S|IQ1_M|UD-Q4_K_XL`
— os nomes que o `--help` dele mostra. Com 32 GB de RAM: `--family coder --model IQ1_M`.)

O que acontece aqui: detecta a placa pelo KFD, instala Python + ROCm (dentro do `.venv`, se não houver
ROCm 7 de sistema), **compila o engine para a sua arquitetura (10–20 min)** e prepara o pack + a camada
MTP (~6 GB). O `--vram-reserve-mib 3072` já grava a reserva de 3 GiB no JSON — é o ajuste que evita o
desktop congelar, então vale passar na hora em vez de editar depois.

Dois ajustes que o setup **não** faz por você (edite o `strata-iq2_xs.json` gerado):

```json
"sampling": { "temperature": 0.6, "top_p": 0.95, "top_k": 20, "min_p": 0.0, "presence_penalty": 1.5 },
"expert_profile_save": "expert-profile-learned-iq2_xs.bin",
"expert_profile_save_every": 10
```

- O bloco `sampling`: sem ele, o motor fica em *greedy* quando o cliente não manda temperatura, e o modo
  reasoning entra em loop de repetição ([docs/04 §4.2](docs/04-strata.md)).
- O `expert_profile_save`: faz o próximo start começar sabendo quais experts o **seu** uso chama.

Compare os argumentos dos dois JSONs (o que falta/roda é `--max-context`, `--kv`, `--kv-resident`,
`--ple-gguf`) com o exemplo versionado:
[`config/strata/strata-iq2_xs.json`](config/strata/strata-iq2_xs.json).

---

## 4. llama-swap (o roteador)

```bash
mkdir -p ~/llama-engines/llama-swap && cd ~/llama-engines/llama-swap
ASSET=$(curl -s https://api.github.com/repos/mostlygeek/llama-swap/releases/latest \
        | grep -o 'https://[^"]*linux_amd64.tar.gz')
curl -sL -o llama-swap.tgz "$ASSET" && tar xzf llama-swap.tgz && rm llama-swap.tgz
./llama-swap -version
#   version: v262 (079c35a), built at 2026-10-03T06:33:47Z     ← o tar traz o binário na raiz
```

Nós rodamos a v260; a v262 é a mais recente e não mudou nada que este guia use (`-validate`,
`-watch-config`, macros, `ttl`). Para ARM troque o filtro por `linux_arm64`.

Instale o config deste repo (ajustado no passo 1) e valide **antes** de subir serviço:

```bash
cp /tmp/local-ai-stack/config/llama-swap.yaml ~/llama-engines/llama-swap/config.yaml
~/llama-engines/llama-swap/llama-swap -config ~/llama-engines/llama-swap/config.yaml -validate
# esperado: config is valid: 17 model(s), 0 peer(s)
```

Ajuste os três caminhos dos macros para o seu (`vbin`, `rbin`, `cbin`, `M`) e remova os perfis que não
correspondem a nada na sua máquina — perfil que aponta para binário inexistente só dá erro no clique.
Se o `cbin` (PR ggml#29887) não existe para você, troque `${cbin}` por `${rbin}` e apague os
`--moe-cache-mib`.

Unit de usuário — aqui o `ulimit -l unlimited` é necessário, porque `mlock` e os experts pinados pedem memória travada:

```bash
cp /tmp/local-ai-stack/config/systemd/llama-swap.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now llama-swap.service
curl -s localhost:8082/v1/models | python3 -m json.tool | head
```

---

## 5. Templates `.jinja`

```bash
cp /tmp/local-ai-stack/templates/*.jinja ~/llm/templates/
pip install jinja2 gguf
python3 /tmp/local-ai-stack/templates/check_render.py     # 12 checagens, sem subir modelo
```

O `qtpl` do config aponta para `~/llm/templates/qwen3.8-template.jinja`. Se você usar um GGUF de outra
família, **não** reutilize este template: compare com o embutido

```bash
python3 -c "
from gguf import GGUFReader
r = GGUFReader('arquivo.gguf')
print(r.fields['tokenizer.chat_template'].contents())" > embutido.jinja
diff -u embutido.jinja ~/llm/templates/qwen3.8-template.jinja | head -30
```

No Qwen3.8-27B que usamos, o `diff` mostra as duas mudanças de comportamento que justificam o template
próprio: o embutido tem default `xhigh` (o nosso, `low`) e o ramo de instruções de cada esforço. As
demais linhas da diferença são o caminho de visão, que não usamos no perfil de texto.

---

## 6. OpenCode (o agente)

```bash
curl -fsSL https://opencode.ai/install | bash      # ou: npm install -g opencode-ai
opencode --version                                  # nosso v2.0.23; v2 é o que os configs assumem
```

Cole o provider deste repo no `~/.config/opencode/opencode.json`
([`config/opencode-provider.json`](config/opencode-provider.json)):

```bash
python3 - <<'PY'
import json, pathlib, shutil
cfg_path = pathlib.Path.home() / ".config/opencode/opencode.json"
cfg = json.loads(cfg_path.read_text()) if cfg_path.exists() else {}
novo = json.loads((pathlib.Path("/tmp/local-ai-stack/config/opencode-provider.json")).read_text())
cfg.setdefault("provider", {})["llama-cpp"] = novo["llama-cpp"]
cfg_path.parent.mkdir(parents=True, exist_ok=True)
shutil.copy2(cfg_path, str(cfg_path) + ".bak") if cfg_path.exists() else None
cfg_path.write_text(json.dumps(cfg, indent=2, ensure_ascii=False) + "\n")
print("provider llama-cpp gravado")
PY
```

Pontos que economizam horas ([docs/05](docs/05-opencode.md)):

- `variants` é **objeto**, não array — array descarta o provider inteiro.
- `limit.context` = `-c`/`--max-context` real (confirme em `curl localhost:8082/props`... por perfil,
  no backend direto).
- `output` 32768 para quem planeja; 8192 para o resto.
- `opencode reload` depois de editar config (restart de serviço com a TUI aberta congela a TUI).

E o sync de modelos + timer:

```bash
cp /tmp/local-ai-stack/scripts/sync-opencode-models.py ~/.local/bin/ && chmod +x ~/.local/bin/sync-opencode-models.py
python3 ~/.local/bin/sync-opencode-models.py --dry-run
cp /tmp/local-ai-stack/config/systemd/opencode-sync-models.{service,timer} ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now opencode-sync-models.timer
```

---

## 7. Teste de fumaça, por camada (nessa ordem)

```bash
# 7.1 roteador vivo?
curl -s localhost:8082/v1/models | grep -o '"id":"[^"]*"' | head

# 7.2 reserva (carrega em ~10 s, 15,4 GB de VRAM) — texto
curl -s localhost:8082/v1/chat/completions -H 'content-type: application/json' -d '{
  "model":"qwen3.8-gsq-s","messages":[{"role":"user","content":"diga olá em uma palavra"}]}' | head -c 400

# 7.3 principal (carga de 35–90 s, ~46–50 GB de RAM) — e aqui você vê a tela engasgar se a
#     reserva de VRAM estiver errada
curl -s localhost:8082/v1/chat/completions -H 'content-type: application/json' -d '{
  "model":"strata-flash-next","messages":[{"role":"user","content":"1+1?"}]}' | head -c 400
cat /sys/class/drm/card1/device/mem_info_vram_used   # deve sobrar ≥ 3 GiB

# 7.4 tool calling (é onde modelo "rápido" reprova):
curl -s localhost:8082/v1/chat/completions -H 'content-type: application/json' -d '{
  "model":"strata-flash-next","messages":[{"role":"user","content":"liste os arquivos de /tmp"}],
  "tools":[{"type":"function","function":{"name":"shell","parameters":{
    "type":"object","properties":{"cmd":{"type":"string"}},"required":["cmd"]}}}]}' | grep -o 'tool_calls.\{0,120\}'

# 7.5 agente de verdade, com um dos fixtures deste repo:
cd /tmp/local-ai-stack
./fixtures/bootstrap.sh api-tarefas /tmp/meu-teste
cd /tmp/meu-teste && echo '{"model":"llama-cpp/strata-flash-next"}' > opencode.json
echo opencode.json >> .git/info/exclude
pnpm install --frozen-lockfile
# config isolado (cópia do seu, com os MCPs desligados): sem isso, um MCP remoto lento
# trava a inicialização e você acha que o culpa é do modelo
mkdir -p /tmp/xdg-teste/opencode
cp ~/.config/opencode/opencode.json /tmp/xdg-teste/opencode/
python3 -c "import json,pathlib;p=pathlib.Path('/tmp/xdg-teste/opencode/opencode.json');d=json.loads(p.read_text());d['mcp']={};p.write_text(json.dumps(d,indent=2,ensure_ascii=False))"

XDG_CONFIG_HOME=/tmp/xdg-teste opencode run --standalone --auto --model 'llama-cpp/strata-flash-next#off' \
  "$(cat /tmp/local-ai-stack/fixtures/PROMPT-EXECUTOR.txt)"
cd /tmp/local-ai-stack && ./fixtures/verify.sh api-tarefas /tmp/meu-teste
```

Esperado no 7.5: tarefas marcadas, um commit por tarefa e o aceite **passando** (6/6). Se ele rodar e
não commitar nada, o problema costuma ser prompt aberto demais — use o prompt plano do fixture
([docs/05 §5.6](docs/05-opencode.md)).

---

## 8. Checklist antes de usar de verdade

- [ ] `-validate` passou no config do llama-swap.
- [ ] `check_render.py` passou (12/12) e você não trocou template entre famílias.
- [ ] `ia status` mostra ≥ 2–3 GiB de VRAM livres com o principal carregado.
- [ ] Sampling explícito em **todo** motor (llama.cpp e Strata).
- [ ] `ttl: 600` nos perfis que não precisam ficar pinados; o principal você decide (0 = nunca).
- [ ] Rodou o fixture `api-tarefas` até o fim e o aceite veio verde.
- [ ] Sabe onde está o log de cada coisa: `journalctl --user -u llama-swap.service`,
      `curl -s localhost:8082/logs`, `~/llm/Strata/strata-*.log`.
- [ ] Leu [docs/07-problemas.md](docs/07-problemas.md) — 90% do que vai dar errado já está lá.

---

## 9. Se der errado nas primeiras 2 horas

| Sintoma | Causa provável | Onde está a correção |
|---|---|---|
| Tela congela / apps morrem ao carregar o modelo | reserva de VRAM baixa | `--vram-reserve-mib 3072`, `--fit-target 3072` ([docs/01 §1.4](docs/01-maquina.md)) |
| 1–2 t/s sem erro | pesos maiores que a RAM | troque de quant ([docs/02 §2.1](docs/02-modelos.md)) |
| Modelos somem do OpenCode | `variants` em array | [docs/05 §5.2](docs/05-opencode.md) |
| Modelo repetindo o mesmo comando | greedy (sem `sampling`) | [docs/04 §4.2](docs/04-strata.md) |
| `exceed_context_size_error` | `limit.context` > `-c` real | [docs/05 §5.1](docs/05-opencode.md) |
| Load abortado pelo llama-swap | `healthCheckTimeout` curto | [docs/03 §3.4](docs/03-llama-swap.md) |
| Resposta cortada no meio | `limit.output` | [docs/05 §5.6](docs/05-opencode.md) |

---

## 10. Atualizar

```bash
cd ~/llm/Strata && ./update.sh          # engine e settings, sem subir o modelo
cd ~/llama-engines/llama.cpp && git pull && cmake --build build-rocm -j"$(nproc)" && cmake --build build-vulkan -j"$(nproc)"
# depois revalide o que importa: um fixture e as flags de MTP (o ganho muda por versão)
python3 /tmp/local-ai-stack/templates/check_render.py
~/llama-engines/llama-swap/llama-swap -config ~/llama-engines/llama-swap/config.yaml -validate
```

Regra chata que economiza dias: **atualize o llama.cpp antes de tunar de novo**. Os números deste repo
foram medidos em `f1cee99` (llama.cpp) e no engine 0.1.39 do Strata; em versão nova, o ótimo de MTP e o
`t/s` mudam.

---

## 11. E no Windows?

Não medimos esta stack inteira em Windows, então não prometo números. O que muda, segundo o upstream de
cada peça:

| Peça | No Windows |
|---|---|
| Strata | baixe o repositório e dê dois cliques em `START-HERE.bat` (engine HIP pronto, sem ROCm nem compilador; precisa do driver Adrenalin atual) |
| llama.cpp | build normal; Vulkan é o caminho sem dor em AMD (o ROCm/HIP de RDNA4 em Windows ainda é novo) |
| llama-swap | `llama-swap_windows_amd64.zip` nos releases; mesmo `config.yaml`, mas ajuste os caminhos (ex.: `C:/Users/voce/models`) e as barras |
| OpenCode | a doc oficial recomenda rodar dentro de WSL2 por performance e compatibilidade |
| Limites | o `mlock`/pin de RAM se comporta diferente (grandes alocações pinadas podem falhar mesmo com RAM livre); confira o log do engine no primeiro load |

Se você rodar em Windows e medir, mande os números: a tabela de quant por RAM deste repo é toda de
Linux/AMD.
