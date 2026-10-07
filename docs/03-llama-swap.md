# 3. llama-swap — o roteador de modelos

Um servidor OpenAI-compatível que **troca o modelo sob demanda**: você expõe uma porta só
(`127.0.0.1:8082`), cada modelo do seu config vira um nome em `/v1/models`, e o llama-swap liga o
backend certo quando alguém pede, descarrega quando fica ocioso.

- Projeto: https://github.com/mostlygeek/llama-swap (MIT, binário único em Go)
- Versão que usamos: **v260** (`fcefa7b`)
- Por que não um `llama-server` fixo: com 16 GB de VRAM só cabe **um** modelo por vez. Sem trocar
  automaticamente, cada troca de modelo seria "matar processo, editar script, reza".

## 3.1 Instalar

```bash
# binário releases do projeto (ou: go build ./cmd/llama-swap)
mkdir -p ~/llama-engines/llama-swap && cd $_
# baixe llama-swap-<versao>-linux-amd64.tar.gz, extraia aqui
./llama-swap -version

cp /caminho/deste/repo/config/llama-swap.yaml ./config.yaml   # edite /home/YOU antes!
./llama-swap -config ./config.yaml -validate
# → config is valid: 17 model(s), 0 peer(s)
```

`-validate` é a sua primeira linha de defesa: ele parseia macros e perfis **sem** ligar nada. Ele
não exige que os `.gguf` existam, então um config validado ainda pode te dar erro em tempo de load.

## 3.2 Rodar como serviço de usuário (systemd)

```ini
# ~/.config/systemd/user/llama-swap.service
[Unit]
Description=llama-swap (user) — roteador de modelos locais :8082
After=network.target

[Service]
ExecStart=/bin/sh -c 'ulimit -l unlimited 2>/dev/null; exec $HOME/llama-engines/llama-swap/llama-swap -config $HOME/llama-engines/llama-swap/config.yaml -watch-config -listen 127.0.0.1:8082'
Restart=on-failure
RestartSec=3

[Install]
WantedBy=default.target
```

```bash
systemctl --user daemon-reload
systemctl --user enable --now llama-swap.service
loginctl enable-linger "$USER"     # sobe sem você ter feito login gráfico (útil em headless)
```

O `ulimit -l unlimited` não é decorativo: o llama.cpp usa `--load-mode mmap+mlock` e o Strata pina
experts na RAM. Com o `memlock` padrão (8 MiB) o pinnamento falha sem aviso e você perde performance ou
leva erro de alocação. Confira com `ulimit -l` dentro do serviço.

Mantenha o `-listen` em `127.0.0.1`. Para expor na LAN/WSL, prefira um proxy dedicado (socat) a abrir o
llama-swap direto.

## 3.3 Estrutura do config

```yaml
healthCheckTimeout: 600   # MoE grande leva 35–90 s para carregar; o padrão derruba o load
startPort: 10001          # cada backend recebe uma porta livre a partir daqui

macros:                   # substituição literal de texto, SEM shell, SEM `~`
  vbin: /home/YOU/llama-engines/llama.cpp/build-vulkan/bin/llama-server
  M: /home/YOU/models
  fitmoe_desk: "--fit on --fit-target 3072 --parallel 1 -b 4096 -ub 2048 ..."
  sampling_qwen38_unif: "--temp 1.0 --top-p 0.95 --top-k 20 --min-p 0.05 --presence-penalty 1.5"

models:
  "qwen3.8-gsq-s":
    name: "Qwen3.8 27B · IQ3_S-MTP · Vulkan · raciocínio pelo seletor"
    model: ${M}/qwen3.8-27b/Qwen3.8-27B-GSQ-RCO-IQ3_S-mtp.gguf
    filterGguf: false
    ttl: 600
    cmd: ${vbin} --model ${M}/qwen3.8-27b/... --port ${PORT} -c 128000 ${mtp2} ${cacheopt} ...
```

Macros que usamos e **por que existem** (todos no `config/llama-swap.yaml`):

| Macro | Conteúdo | Motivo |
|---|---|---|
| `vbin` / `rbin` / `cbin` | llama-server Vulkan / ROCm / ROCm+PR #29887 | backend certo por tipo de modelo; `cbin` tem cache de experts na VRAM (+22% t/s medido em MoE) |
| `fitgpu` | `--fit on --fit-target 1024` | denso que cabe inteiro na VRAM, sem desktop pesado |
| `fitmoe_desk` | `--fit on --fit-target 3072 ... --load-mode mmap --lazy-mode on` | MoE grande **com desktop na mesma placa** (os 3 GiB vêm do defeito da tela congelar) |
| `mtp2` | `--spec-type draft-mtp --spec-draft-n-max 2 --spec-draft-p-min 0 -ctkd q8_0 -ctvd q8_0` | baseline comunitário para AMD RDNA4 16 GB; `p-min` só se a aceitação cair |
| `cacheopt` | `--cache-reuse 256 --ctx-checkpoints 32 --metrics` | reuso de prefixo + rollback de cauda + Prometheus |
| `budget` | `--reasoning-budget 4096 --reasoning-budget-message "..."` | teto de raciocínio **com** frase que faz o modelo fechar o thinking sozinho (sem corte seco) |
| `qtpl` | chat template `.jinja` fora do GGUF | quando o template embutido tem bug ou você quer effort control |
| `sampling_*` | os valores oficiais por família/modo | nunca depender do default do motor — ver [docs/06](06-tuning.md) |

## 3.4 Os knobs que realmente importam

| Knob | O que faz | O que aprendemos |
|---|---|---|
| `--parallel` / `parallel` | quantas sessões o backend atende ao mesmo tempo | no llama.cpp o `-c` é **dividido** entre os slots; no Strata cada slot leva o contexto inteiro — ver [docs/04 §4.6](04-strata.md) |
| `ttl: 600` | descarrega após 600 s ocioso | devolve ~40 GB de RAM; sem isso, o segundo modelo grande não entra |
| `ttl: -1` / `ttl: 0` | ⚠️ **-1 não significa "nunca"**: significa *herdar o TTL global*. Quem significa "nunca" é **0**. Como não definimos `ttl` global (e o global padrão é 0), `-1` cai em "nunca" por acaso — se você poner um `ttl:` global, todos os perfis `-1` passam a descarregar | deixar o principal em `ttl: 0` explícito se você mexer no global |
| `healthCheckTimeout` | espera o backend responder no health check | 600 s para MoE; valor baixo = llama-swap mata o load no meio e você vê "failed to start" |
| `checkEndpoint: none` | não faz health check | use em perfil "proxy" (aponta para um servidor já rodando); o llama-swap **não** tem perfil proxy puro, então a gente usa `cmd: sleep infinity` |
| `-watch-config` | recarrega ao salvar | ótimo, e perigoso **durante um teste** (derruba stream em uso) |
| `filterGguf: false` | não tenta ler metadados do GGUF | necessário quando o arquivo ainda não existe ou o backend não conhece a arquitetura |

## 3.5 Endpoints que usamos no dia a dia

```bash
curl -s localhost:8082/v1/models | python3 -m json.tool   # o que está publicado
curl -s localhost:8082/running                            # quem está carregado agora (e o estado)
curl -s localhost:8082/logs | tail -50                    # log do backend (o erro real fica aqui)
curl -s localhost:8082/unload                             # descarrega tudo devolve a RAM/VRAM
curl -s localhost:10001/props | head                      # n_ctx real, slots, KV — conferira o que o modelo achou
```

`/props` é o melhor detector de autoengano do setup: mostra o `n_ctx` efetivo. Foi assim que descobrimos
que `--parallel 2` divide o `-c` (pedia 128k, tinha 64k por slot).

Dois comportamentos que parecem bug e são o desenho:

- **Reiniciar o llama-swap restaura o modelo que estava carregado.** Se você descarregou de propósito e
  reinicia o serviço, ele tenta subir tudo outra vez — descarregue antes de reiniciar.
- **Um perfil "pinado" volta do unload sozinho se houver cliente conectado.** Nosso principal voltava a
  cada `/unload` porque uma aplicação Java e o Chrome mantinham conexões em `:8082`: o llama-swap se
  recupera da desconexão re-emindo o stream, e isso dispara o load. Diagnóstico: `ss -tnp | grep 8082`
  antes de concluir que o unload "não funcionou". 

## 3.6 Checklist de um perfil novo

1. `gguf` no disco e legível.
2. Copie um perfil existente e mude **só**: `model`, `-c`, KV (`-ctk`/`-ctv`), macro de sampling e o
   backend (`vbin`/`rbin`/`cbin`).
3. `llama-swap -config ... -validate`.
4. Load manual **antes** de confiar no perfil, com a mesma flag de folga:
   `llama-server <cmd sem ${PORT}> --port 10999` e observe `rocm-smi --showmeminfo vram`.
5. `curl localhost:8082/<perfil-novo>` via `/v1/chat/completions` e confira o `finish_reason` e o
   `n_ctx` em `/props`.
6. Teste **tool calling** (uma chamada com `tools`), não só texto — muito modelo bonito reprovou aí.
7. Rode uma prova de agente ([docs/08](08-avaliacao.md)) antes de promover o modelo a principal.

> O `config/llama-swap.yaml` publicado é uma cópia sanitizada do nosso config vivo: mesmos macros e
> os 17 perfis ativos, com os caminhos reais trocados por `/home/YOU` e os perfis comentados
> (reprovados) de fora — o histórico deles está em [docs/02](02-modelos.md).
