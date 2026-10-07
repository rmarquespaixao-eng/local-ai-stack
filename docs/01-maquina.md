# 1. A máquina, o orçamento de memória e as regras de ouro

Todo número deste repo sai de uma máquina específica. Publiquei ela aqui inteira porque a
configuração **depende** dela: o mesmo GGUF com a mesma flag vai bem num rig e congela o desktop
num outro. Primeiro leia a sua, depois copie a nossa.

## 1.1 O rig de referência

| Item | Valor |
|---|---|
| CPU | AMD Ryzen 9 5950X (16c/32t), `nproc` = 32 |
| RAM | 56 GB físicos, **54 GB utilizáveis** — 4 pentes mistos, canais **24/32 GB assimétricos**, DDR4-3200 CL22 |
| Swap | zram 54,8 GB (cuidado: esconde OOM e dá falso positivo de "swap saudável") |
| GPU | AMD Radeon **RX 9070 XT**, 16 GB (gfx1201 / RDNA4) — `/sys/class/drm/card1` |
| VRAM real utilizável | **16.302 MiB** totais, **~15.92 GiB** úteis (o desktop ocioso come 460–550 MiB) |
| Banda de RAM medida | **~41,5 GB/s** (pico com 2–4 threads; mais threads = *menos* banda) |
| SO | CachyOS (Arch), kernel 7.2.8-1-cachyos, KDE/Wayland no mesmo GPU |
| ROCm / Mesa | ROCm 7.2 em `/opt/rocm` · Mesa 26.2.3 |
| Disco dos modelos | NVMe dedicado (WD Black SN850 916 GB) montado em `~/models` |
| Node / Java / Python | Node 24 · JDK 27 · Python 3.12 (`uv`) |

**Arquitetura relevante:** a GPU é compartilhada com o desktop. Isso corta a metade o ganho que
você vê em benchmarks headless (regra nº 7 da comunidade MTP) e é a causa direta dos congelamentos
de tela que quase nos fez desistir da stack.

## 1.2 Orçamento: como decidimos o que cabe

A conta que fizemos em cada perfil, sempre com folga:

```
VRAM total útil          ~ 15.920 MiB
- desktop ocioso         ~   500 MiB
- margem que deixamos    ~ 3.072 MiB   (abaixo disso → page allocation failure)
= sobra para pesos+KV    ~ 12.350 MiB
```

E para modelos que **não** cabem na VRAM:

```
RAM disponível (54 GB) - Residente necessário (pesos dos experts em CPU) ≥ ~10 GB de folga
```

Foi essa conta que reprovou o `gpt-oss-120b` (59 GB de pesos, só ~49 GB residentes) e que definiu o
teto de quant do Strata: com 54 GB de RAM, **IQ2_XS é o máximo realista** (ver
[docs/02](02-modelos.md)).

## 1.3 Meça a SUA máquina antes de copiar qualquer flag

```bash
# VRAM total/livre (AMD)
rocm-smi --showmeminfo vram
# ou, via sysfs, em MiB:
cat /sys/class/drm/card1/device/mem_info_vram_used /sys/class/drm/card1/device/mem_info_vram_total

# RAM utilizável e o quanto já está em uso
free -g

# banda de RAM (proxy simples de leitura sequencial — não é medida de banda)
dd if=/dev/zero of=/dev/null bs=1M count=40000 2>/dev/null
# para banda real, rode um teste STREAM-like com 2–4 threads (com mais threads o número costuma cair)

# residência de um GGUF grande (é o que entrega lentidão sem erro na tela): quantos GB estão na RAM
fincore ~/models/qwen3.8-flash-next/IQ2_XS/*.gguf
vmtouch -l ~/models/qwen3.8-flash-next/IQ2_XS/*.gguf   # se estiver em page cache
```

Sinais de que você estourou o orçamento (todos ocorreram aqui):

| Sintoma | O que está acontecendo |
|---|---|
| Tela congela 2–10 s sob carga | `amdgpu_bo_pin` sem VRAM; confira `journalctl -k \| grep -i 'page allocation failure'` |
| Geração a 1–2 t/s sem erro | pesos sendo relidos do NVMe a cada token (modelo > RAM) |
| Travada de vários minutos no primeiro load | normal em MoE grande: o motor está lendo 35–55 GB para a RAM |
| PC reinicia sozinho sob carga de IA | pode ser CPU/estabilização, não a GPU (no nosso caso: MCE não corrigido num núcleo, ver [docs/07](07-problemas.md)) |

## 1.4 As regras de ouro

1. **≥ 2–3 GB de VRAM livres, sempre.**
   llama.cpp: `--fit on --fit-target 3072` · Strata: `--vram-reserve-mib 3072`.
   Com `--fit-target 1024` deixamos a placa em 15,2/16,3 GB e o compositor/Chrome passaram a falhar
   alocação. O Strata no padrão deixava ~700 MiB e travava a máquina.
2. **Um modelo grande por vez.** O principal ocupa ~46–50 GB de RAM. No llama-swap, `ttl: 600` em
   todo perfil que não precisa estar sempre quente.
3. **`--parallel 1` no llama.cpp em agente.** Ele **divide** o `-c` entre os slots: com `--parallel 2`
   e `-c 128000`, cada agente enxerga 64k e a sessão morre com `finish_reason: length` — e 1 slot não
   custa VRAM extra. (No Strata é o contrário: cada slot leva o contexto **inteiro** — ver docs/04.)
4. **Sampling explícito em todo perfil.** Os padrões do motor raramente coincidem com a model card, e
   greedy em modo reasoning costuma virar loop de repetição.
5. **Medir no `llama-server`, não no `llama-bench`.** O server gasta ~2 GB a mais de VRAM e tem
   preferências diferentes de ubatch. Benchmark ≠ servidor.
6. **Evite editar o config do llama-swap durante um teste.** Com `-watch-config` ele recarrega e derruba o
   stream em uso (`stream ended without finish_reason`). Perdemos uma avaliação inteira assim.
7. **Reprovado fica registrado, não apagado.** Mover o GGUF para uma pasta `REPROVADOS/` e comentar
   o perfil com `# [REPROVADO <data> — <motivo>]`. Daqui a 3 meses você vai querer saber por que
   aquilo saiu.

## 1.5 Layout de diretórios que usamos

Tudo parametrizável; só evite o disco do sistema para os pesos (leitura sequencial de 30–80 GB).

| Caminho | Conteúdo |
|---|---|
| `~/models/` | GGUFs por modelo (`qwen3.8-flash-next/IQ2_XS/…`), mais `REPROVADOS/README.md` |
| `~/llama-engines/` | builds do llama.cpp (`build-vulkan/`, `build-rocm/`) e o binário+config do llama-swap |
| `~/llm/Strata/` | motor Strata, JSONs de config, wrapper `strata-swap.sh`, logs |
| `~/llm/Strata-data/` | packs gerados pelo setup e cabeça MTP |
| `~/llm/templates/` | chat templates `.jinja` customizados |
| `~/.config/llama-swap/config.yaml` | symlink/origem do config (mantenha um só arquivo real) |
| `~/.config/opencode/opencode.json` | providers, limites, MCPs do agente |

> **Por que isso importa:** o llama-swap não expande `~` nos macros. Use caminhos absolutos — e
> deixe-os concentrados nos macros (`M`, `vbin`, `cbin`) para trocar uma linha só.
