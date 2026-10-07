#!/usr/bin/env bash
# fit-check — "vai caber?" antes de subir o llama-server.
#
#   scripts/fit-check.sh <llama-fit-params> <modelo.gguf> <ctx> [kv] [ngl]
#   scripts/fit-check.sh ~/llama-engines/llama.cpp/build-rocm/bin/llama-fit-params \
#       ~/models/qwen3.8-27b/Qwen3.8-27B-GSQ-RCO-IQ3_S-mtp.gguf 131072 q8_0 99
#
# Imprime a estimativa do llama.cpp (model | context | compute, em MiB), a VRAM livre AGORA e a
# folga. Calibramos o oráculo contra `rocm-smi` real: ±2%.
#
# O que ele NÃO modela (some na mão):
#   - mmproj de modelo multimodal: ~1,7 GiB
#   - o desktop na mesma placa: 460–550 MiB ocioso, mais quando o navegador acorda
#   - buffers de backend que só aparecem em carga (por isso a folga mínima é 3 GiB, não 0)
set -uo pipefail

BIN="${1:?uso: fit-check.sh <llama-fit-params> <gguf> <ctx> [kv] [ngl]}"
MODEL="${2:?falta o .gguf}"
CTX="${3:-131072}"
KV="${4:-q8_0}"
NGL="${5:-99}"

[[ -x "$BIN" ]] || { echo "não encontrei o binário: $BIN" >&2; exit 2; }
[[ -f "$MODEL" ]] || { echo "não encontrei o modelo: $MODEL" >&2; exit 2; }

used_file=$(ls /sys/class/drm/card*/device/mem_info_vram_used 2>/dev/null | head -1)
total_file=$(ls /sys/class/drm/card*/device/mem_info_vram_total 2>/dev/null | head -1)

echo "== estimativa ($MODEL, -c $CTX, KV $KV, -ngl $NGL) =="
"$BIN" --model "$MODEL" -fitp on -c "$CTX" -ctk "$KV" -ctv "$KV" -ngl "$NGL" 2>&1 |
  grep -E '^(ROCm|CUDA|Vulkan|Metal|Host|CPU)' |
  while read -r dev model context compute; do
    printf '  %-7s model=%7s  ctx=%7s  compute=%7s MiB\n' "$dev" "$model" "$context" "$compute"
    if [[ "$dev" != "Host" && -n "${used_file:-}" ]]; then
      need=$(( model + context + compute ))
      awk -v need="$need" -v u="$(cat "$used_file")" -v t="$(cat "$total_file")" 'BEGIN{
        livre = (t-u)/1024/1024; folga = livre - need;
        printf "  VRAM livre agora %.0f MiB | precisa %d MiB | FOLGA %.0f MiB -> %s\n",
          livre, need, folga, (folga < 3072 ? "APERTA (meta ≥ 3 GiB p/ desktop)" : "ok")
      }'
    fi
  done

echo
echo "Confirmar no load real: llama-server <cmd> --port 10999  e, durante a geração,"
echo "  rocm-smi --showmeminfo vram   |   cat /sys/class/drm/card1/device/mem_info_vram_used"
echo "E confira o contexto que o modelo achou:  curl -s localhost:10999/props | grep -E 'n_ctx|total_slots'"
