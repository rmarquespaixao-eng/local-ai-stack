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

### Os quatro ajustes que tivemos que fazer à mão

1. **`--vram-reserve-mib 3072`** *(necessário se a placa também é a do seu desktop)*
   O padrão deixava ~700 MiB livres; o compositor pedia VRAM, o `amdgpu` movia memória de GPU para
   RAM (GTT) e a tela congelava, ou o OOM killer encerrava o `plasmashell`. O upstream descreve o mesmo
   problema (#560, #516) e recomenda exatamente `--vram-reserve-mib 3072` em Linux desktop.

2. **Bloco `sampling`** *(necessário — nos custou duas sessões em loop).*
   Sem esse bloco, e com um cliente que não manda temperatura (o OpenCode não manda), o engine fica
   em **greedy** e o modo reasoning entra em repetição: vimos o mesmo comando ser rodado 46× e 140×.
   Depois de pôr `0.6 / 0.95 / 20 / min_p 0 / presence 1.5` (thinking) e `0.7 / 0.8 / 20 / presence
   1.5` (instruct): **zero loops em 288 chamadas**. Regra que ficou: *todo motor novo precisa de
   sampling explícito, em vez de depender do padrão.*

3. **`--kv int8 --kv-resident 32768`** (streaming da KV). A KV mora na RAM e só a parte lida pela
   atenção fica na VRAM → sobra VRAM para experts. Custo ~13,7 KB de RAM por token de contexto
   (~1,7 GB a 128k). Sem isso, a 256k o cache de experts encolhe e o decode cai bastante.
   Quer mais qualidade de KV? `--kv k8v4` (chave 8 bits, valor 4 bits rotacionado): 23% menos
   memória de KV com os mesmos needle tests.

4. **`--expert-cache auto`** — deixe sem valor fixo. O `auto` dimensiona e re-confere depois que os
   slots são escritos; um cache maior que a VRAM disponível não falha, só fica ~7× mais lento (números no
   upstream). Ajuste a reserva, não o cache.

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
**dá spawn ao engine** — encerrar só o Python deixa o engine órfão, segurando ~14 GB de VRAM. O wrapper resolve
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
| `"min_free_vram_mib": 11000` | só carrega se houver aquela VRAM livre; senão responde **503** "GPU em uso", em vez de invadir o espaço do jogo |
| `"before_load": "cmd"` | roda um comando antes de carregar (ex.: descarregar o modelo de outro servidor) |

`POST /unload` e `POST /load` (com `Content-Type: application/json`) também funcionam. E o
[`scripts/ia.sh`](../scripts/ia.sh) faz o "desliga tudo para ir jogar" de uma vez.

> **Se você usar uma unit systemd para o Strata:** limite memória com `MemoryMax` em vez de
> `MemoryHigh`. O `MemoryHigh` conta o page cache que o `--mmap-experts` lê, e nesse cenário um
> prompt de 16k ficou ~8 min parado em `pread` (relato documentado no upstream; #750/#920 também
> discutem timeouts de verify por suspensão de fila KFD sob reclaim).

## 4.6 Rodar duas conversas grandes em paralelo (batching)

**O que é:** a chave `parallel` no JSON do servidor vira `--batch N` no engine (faixa 2–8; também
aceita `--slots`). Diferente do llama.cpp, **o contexto não é dividido entre os slots**: cada slot
enxerga o contexto inteiro do perfil. Num perfil 256k, dois slots = duas conversas de 256k.

**Passo a passo:**

1. Use o perfil de **contexto longo** (`strata-iq2_xs-256k.json` / o equivalente do Swift). É onde o
   batching compensa: conversa grande é exatamente o que não cabe em contexto curto.
2. Acrescente a chave ao mesmo nível de `model_name`:

   ```json
   {
     "exe": "/home/YOU/llm/Strata/engine/strata",
     "args": [ "…", "--max-context", "262144", "--kv", "int8", "--kv-resident", "32768" ],
     "model_name": "swift-1.5-iq2_xs-256k",
     "parallel": 2,
     "sampling": { "temperature": 0.6, "top_p": 0.95, "top_k": 20, "min_p": 0.0, "presence_penalty": 1.5 }
   }
   ```

3. Confirme a VRAM livre **antes** de recarregar: cada slot custa ~0,95 GiB de VRAM e mais a KV
   pinada em RAM (~3 GiB por slot a 256k com `--kv-resident 32768`).
4. Recarregue o perfil. Com `-watch-config`, salvar o `config.yaml` do llama-swap já reinicia o
   backend; o JSON do Strata é lido nesse momento. Se quiser forçar sem editar nada:
   `curl -s localhost:8082/unload`.
5. Aponte os **dois clientes para o mesmo modelo** (`llama-cpp/strata-swift-flash-next-256k`). Não
   crie dois perfis apontando para o mesmo JSON: isso sobe dois engines, e dois engines Strata não
   cabem juntos nesta máquina (ver adiante).
6. Leia o log para conferir se pegou (`~/llm/Strata/strata-*.log`):

   ```
   --batch: 2 slot sessions on CUDA0 (0.95 GiB each); 9.88 GiB free
   batch windows of up to 2 sequences (layers [0,48))
   ```

   E no llama-swap: `curl -s localhost:8082/running` deve mostrar o perfil `ready`. Um
   `/v1/chat/completions` de teste em cada cliente responde em paralelo.

**Perfil correspondente no llama-swap** (é só um `cmd` apontando para o JSON com `parallel`):

```yaml
"strata-flash-next-256k-parallel2":
  name: "Qwen3.8-Flash-Next 125B-A6B · IQ2_XS · Strata · 256k · 2 sessões"
  ttl: 600
  cmd: /home/YOU/llm/Strata/strata-swap.sh /home/YOU/llm/Strata/strata-iq2_xs-256k-parallel2.json ${PORT}
```

Um exemplo pronto desse JSON está em
[`config/strata/strata-iq2_xs-256k-parallel2.json`](../config/strata/strata-iq2_xs-256k-parallel2.json)
(identico ao `strata-iq2_xs-256k.json`, com `"parallel": 2`, `model_name` e `log` próprios). Ele **não**
faz parte do `config/llama-swap.yaml` publicado: na nossa máquina quem roda em batch é o perfil 256k do
Swift, e preferimos publicar como exemplo em vez de um perfil que não está em uso.

**Custo medido** (Swift 1.5 IQ2_XS a 256k, 2 slots, RX 9070 XT 16 GB / 54 GB de RAM):

| Item | Valor |
|---|---|
| VRAM por slot | ~0,95 GiB (com 2 slots: 9,88 GiB livres no fim do load) |
| RAM pinada por slot | ~3 GiB (KV `int8` com `--kv-resident 32768`) |
| Duas conversas simultâneas | uma de ~145k e outra de ~198k de tokens, ~45 t/s **somadas**, RAM 51/55 GB sem swap |
| MTP em batch | **desligado** — em batch o slot decodifica 1 token por janela; sozinho, volta ao caminho com MTP |

**Por que 2 e não 5:** tentamos 5. A VRAM cabia (4,74 GiB), mas o load levou a RAM a 54/54 GB e a
máquina entrou em thrash. Voltou para 2, que aguenta duas conversas de ~200k com folga. Como conta
aproximada: `RAM disponível − 10 GB` para os experts, e ~3 GiB de KV pinada por slot em 256k — com
64 GB de RAM dá para 3 firmes; com 96 GB, os 5 voltam ao jogo.

**Quatro coisas que valem junto com o batching:**

- **Dois engines Strata não rodam juntos** (cada um arena ~33 GiB de experts; 33+33 > 54 GB). Antes
  de subir o perfil de contexto longo, descarregue o outro e confirme `pgrep -f engine/strata` vazio.
  O serviço de embeddings (`:8083`) é separado e pode ficar ligado.
- **Perfil de contexto longo com `ttl: 600`** devolve a RAM depois de ~10 min sem tráfego, e o
  principal reassume a GPU na demanda seguinte. Para segurá-lo no ar, mantenha tráfego ou suba o `ttl`.
- **Batch só paga com contexto grande / várias sessões.** Se os dois clientes usam 8k de prompt, os
  ~3 GiB de KV pinada por slot são desperdiçados — melhor um perfil 32k/64k para cada um.
- **`--prefill auto`** já deixa o chunk grande; em batch o prefill continua sequencial por request,
  então o segundo cliente espera o primeiro terminar de ler o prompt.

## 4.7 O que ainda não sabemos

- **IQ3_XXS no Strata com 54 GB de RAM**: o pack está feito e o perfil existe, mas ainda não temos
  medição limpa — prefiro não publicar número adivinhado.
- O `--ple-gguf` dos nossos JSONs do Swift aponta para o shard **1**. Para o GSQ-RCO original o PLE
  é o shard **2** (shard 1 é para o OrcaRouter). Provável erro nosso, não descoberto até esta
  revisão. Se copiar os configs para Swift, confira esse caminho.
