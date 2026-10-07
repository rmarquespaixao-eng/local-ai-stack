# 4. Strata — o motor que faz o Flash-Next voar em 16 GB

- Projeto: https://github.com/Niko1221/Strata (MIT) — motor de inferência **feito para o
  Qwen3.8-Flash-Next** (e suas variantes Swift/Coder), com API OpenAI e Anthropic em `localhost`.
- O que ele faz de diferente: o modelo tem 24.576 experts por camada e só 10 são usados por token.
  O Strata mantém **todos** os experts na RAM, põe na VRAM os que o seu uso real mais pede
  (perfil de experts + cache adaptativo) e o CPU calcula as faltas. É por isso que um 125B roda em
  uma RX 9070 XT de 16 GB.
- No nosso hardware medimos **~45 t/s decode / ~1.100–1.250 t/s prefill** contra ~15–18 / ~450–650
  do llama.cpp ROCm com o mesmo arquivo.

**Custo:** o motor serve para essa família de modelos. Nada de "um Strata para tudo".

## 4.1 Instalar (Linux, AMD — o nosso caso)

```bash
git clone https://github.com/Niko1221/Strata ~/llm/Strata
cd ~/llm/Strata
./setup.sh --backend hip --family qwen --model IQ2_XS --context 131072 \
           --gguf-dir ~/models/qwen3.8-flash-next/IQ2_XS --no-start --yes
```

Notas que valem para nós e para você:

- **Não precisa instalar ROCm.** Com o driver `amdgpu` do kernel basta; sem um ROCm 7 em
  `/opt/rocm`, o setup instala o ROCm dentro do `.venv` a partir dos wheels da TheRock
  (~10 GB, sem sudo). Para gfx1200/gfx1201 usa o index `gfx120X-all`.
- O engine é **compilado na sua máquina** para a sua arquitetura (10–20 min, uma vez).
- `--family` aceita `qwen` (o original), `swift` (fine-tune que pensa menos) e `coder`
  (experts podados, para 32 GB de RAM).
- `--no-start` nos interessa porque quem vai ligar e desligar o motor é o llama-swap, não você.
- Os arquivos vão para `~/llm/Strata-data` (packs, MTP) — deixe em SSD e com 70–120 GB livres.

NVIDIA: o mesmo `setup.sh` pega CUDA pronto (RTX 20/30/40/50); veja o README do projeto.

## 4.2 O arquivo `strata-<modelo>.json`

É o config do servidor: qual binário, quais argumentos do engine, tokenizer, porta, amostragem.
Publicamos os nossos em [`config/strata/`](../config/strata). O essencial:

```json
{
  "exe": "/home/YOU/llm/Strata/engine/strata",
  "args": [
    "--pack", "/home/YOU/llm/Strata-data/packs/iq2_xs",
    "--native", "...IQ2_XS-00001-of-00002.gguf",
    "--ple-gguf", "...IQ2_XS-00002-of-00002.gguf",
    "--expert-profile", "/home/YOU/llm/Strata/data/expert-profile.bin",
    "--expert-cache", "auto",
    "--prefill", "auto",
    "--spec", "4", "--spec-min-p", "0.5",
    "--mtp", "/home/YOU/llm/Strata-data/mtp/rt",
    "--max-context", "131072",
    "--kv", "int8", "--kv-resident", "32768",
    "--vram-reserve-mib", "3072"
  ],
  "tokenizer": "/home/YOU/llm/Strata-data/packs/iq2_xs/tokenizer",
  "model_name": "qwen3.8-flash-next-iq2_xs",
  "backend": "hip",
  "port": 8080,
  "host": "127.0.0.1",
  "env": { "STRATA_HIPBLASLT_TUNING": "/home/YOU/llm/Strata/tools/hip/gfx1201-hipblaslt-100202.txt" },
  "sampling": { "temperature": 0.6, "top_p": 0.95, "top_k": 20, "min_p": 0.0, "presence_penalty": 1.5 },
  "expert_profile_save": "expert-profile-learned-iq2_xs.bin",
  "expert_profile_save_every": 10
}
```

### Os quatro ajustes que a gente teve que fazer à mão

1. **`--vram-reserve-mib 3072`** *(obrigatório se a placa também é a do seu desktop)*
   O padrão deixava ~700 MiB livres; o compositor pedia VRAM, o `amdgpu` movia memória de GPU para
   RAM (GTT) e a tela congelava / o OOM killer matava o `plasmashell`. O upstream descreve o mesmo
   problema (#560, #516) e recomenda exatamente `--vram-reserve-mib 3072` em Linux desktop.

2. **Bloco `sampling`** *(obrigatório — nos custou duas sessões em loop).*
   Sem esse bloco, e com um cliente que não manda temperatura (o OpenCode não manda), o engine fica
   em **greedy** e o modo reasoning entra em repetição: vimos o mesmo comando ser rodado 46× e 140×.
   Depois de por `0.6 / 0.95 / 20 / min_p 0 / presence 1.5` (thinking) e `0.7 / 0.8 / 20 / presence
   1.5` (instruct): **zero loops em 288 chamadas**. Regra que ficou: *todo motor novo precisa de
   sampling explícito; nunca confie no padrão.*

3. **`--kv int8 --kv-resident 32768`** (streaming da KV). A KV mora na RAM e só a parte lida pela
   atenção fica na VRAM → sobra VRAM para experts. Custo ~13,7 KB de RAM por token de contexto
   (~1,7 GB a 128k). Sem isso, a 256k o cache de experts encolhe e o decode despenca.
   Quer mais qualidade de KV? `--kv k8v4` (chave 8 bits, valor 4 bits rotacionado): 23% menos
   memória de KV com os mesmos needle tests.

4. **`--expert-cache auto`** — não fixe. O `auto` dimensiona e **re-confere** depois que os slots
   são escritos; um cache maior que a VRAM disponível não falha, só vira 7× mais lento (está
   documentado no upstream com números). Deixe o `auto` decidir e ajuste a reserva, não o cache.

### Bônus que vale muito no nosso chip

- `STRATA_HIPBLASLT_TUNING` aponta para a tabela de GEMMs do hipBLASLt **da sua versão exata**. Com
  ROCm 7.2.4 (hipBLASLt 1.2.2) em gfx1201, o upstream mede 638 → 1.177 t/s de prefill a 32k com a
  tabela `gfx1201-hipblaslt-100202.txt` contra o caminho hipBLAS puro. O setup só usa a tabela se a
  versão bater; se aparecer "no table, using plain hipBLAS" no log, calibre a sua
  (`cmake --build build-hip --target tune_hipblaslt`).
- `expert_profile_save` + `expert_profile_save_every: 10` (desde o engine 0.1.36): salva o que o
  cache aprendeu sobre **o seu uso** e o próximo start começa dele. É um retrato do seu corpus —
  fica na sua máquina, não versionei.
- `STRATA_HIP_WMMA=1` para gfx1201 com `--kv int8`: +17–31% de prefill nos kernels de atenção em
  matrix cores do RDNA4, decode igual, **mas muda os bits** (texto greedy diverge depois de ~50
  tokens). Não testamos ainda; trate como experimental.

## 4.3 Raciocínio: um perfil só, escolhido pelo cliente

O servidor aceita `reasoning_effort` no request e mapeia `low→low`, `medium→medium`, `high→xhigh`
(`serve/frontend.py`). Junto com um `strata-<modelo>.shared-settings.json` (o default quando o
cliente não manda nada), isso dispensa o par de perfis `-instruct` / `-think`:

```json
// strata-iq2_xs.shared-settings.json  →  padrão = pensa um pouco
{"reasoning_effort": "low"}
```

Fizemos a unificação em 2026-10-05: **um perfil, um sampling, três níveis**. Menos superfície para
config divergir. O alto (`high`, que no motor vira `xhigh`) é o modo de *planejar*; o `none` é o de
*executar*. Executar com raciocínio custou ~45% mais tempo sem nenhum ganho de qualidade medido.

## 4.4 Ligar pelo llama-swap: o wrapper

O llama-swap espera um processo filho que morra com SIGTERM. O servidor do Strata é Python que
**dá spawn no engine** — matar o Python deixa o engine órfão segurando ~14 GB de VRAM. O wrapper resolve
com `setsid` + trap no grupo:

```bash
#!/usr/bin/env bash
# strata-swap.sh <config.json> <porta>
set -u
cd "/home/YOU/llm/Strata"
setsid "/home/YOU/llm/Strata/.venv/bin/python" "/home/YOU/llm/Strata/serve/server.py" \
  --engine strata --config "$1" --port "$2" &
CHILD=$!
stop() { kill -TERM -- -"$CHILD" 2>/dev/null
  for _ in $(seq 20); do kill -0 -- -"$CHILD" 2>/dev/null || exit 0; sleep 0.5; done
  kill -KILL -- -"$CHILD" 2>/dev/null; exit 0; }
trap stop TERM INT HUP
wait "$CHILD"
```

E no config do llama-swap, o perfil fica assim (sem `model:`, porque o arquivo `.gguf` é o Strata
quem abre):

```yaml
"strata-flash-next":
  name: "Qwen3.8-Flash-Next 125B-A6B · IQ2_XS · Strata · PRINCIPAL"
  ttl: -1
  cmd: /home/YOU/llm/Strata/strata-swap.sh /home/YOU/llm/Strata/strata-iq2_xs.json ${PORT}
```

## 4.5 Dividir a placa com o resto da vida

O Strata tem três chaves próprias para isso (além do `ttl` do llama-swap), todas no JSON:

| Chave | Efeito |
|---|---|
| `"idle_unload_s": 600` | descarrega após 10 min sem pedido; o próximo pedido recarrega (segundos, se a RAM não foi usada) |
| `"min_free_vram_mib": 11000` | só carrega se houver aquela VRAM livre; senão responde **503** "GPU em uso" em vez de pisar no jogo |
| `"before_load": "cmd"` | roda um comando antes de carregar (ex.: descarregar o modelo de outro servidor) |

`POST /unload` e `POST /load` (com `Content-Type: application/json`) também funcionam. E o
[`scripts/ia.sh`](../scripts/ia.sh) faz o "desliga tudo para ir jogar" de uma vez.

> **Se você usar unit systemd para o Strata:** limite memória com `MemoryMax`, **nunca**
> `MemoryHigh` — o `MemoryHigh` conta o page cache que o `--mmap-experts` lê, e um prompt de 16k
> ficou 8 min parado em `pread` nesse cenário (relato documentado no upstream, #750/#920 também
> discutem os timeouts de verify por suspensão de fila KFD sob reclaim).

## 4.6 O que ainda não sabemos

- **IQ3_XXS no Strata com 54 GB de RAM**: o pack está feito e o perfil existe, mas o veredito é do
  dono da máquina — não publico número porque não tenho medição limpa.
- O `--ple-gguf` dos nossos JSONs do Swift aponta para o shard **1**. Para o GSQ-RCO original o PLE
  é o shard **2** (shard 1 é para o OrcaRouter). Provável erro nosso, não descoberto até esta
  revisão. Se copiar os configs para Swift, confira esse caminho.
