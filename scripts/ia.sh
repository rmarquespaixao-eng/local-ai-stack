#!/usr/bin/env bash
# ia — liga/desliga a stack local (libera VRAM/RAM; útil antes de jogar ou de rodar outro motor).
#   ia status | ia on | ia off
# Unidades que ele controla: IA_UNITS="llama-swap.service rag-embed.service" (default abaixo)
# Porta do roteador: LLAMA_SWAP_PORT=8082
set -uo pipefail

UNITS=(${IA_UNITS:-llama-swap.service opencode-sync-models.timer})
PORT="${LLAMA_SWAP_PORT:-8082}"
BASE="http://127.0.0.1:${PORT}"

vram() {
  local used total
  used=$(cat /sys/class/drm/card*/device/mem_info_vram_used 2>/dev/null | head -1)
  total=$(cat /sys/class/drm/card*/device/mem_info_vram_total 2>/dev/null | head -1)
  [[ -n "$used" && -n "$total" ]] || { echo "n/d"; return; }
  awk -v u="$used" -v t="$total" 'BEGIN{printf "%.1f/%.1f GB (livre %.1f)", u/1073741824, t/1073741824, (t-u)/1073741824}'
}

status() {
  local u
  for u in "${UNITS[@]}"; do
    printf '  %-24s %s\n' "$u" "$(systemctl --user is-active "$u" 2>/dev/null)"
  done
  printf '  %-24s %s\n' "opencode daemon" "$(pgrep -f 'opencode serve --service' >/dev/null && echo active || echo inactive)"
  printf '  %-24s %s\n' "llama-server" "$(pgrep -c -f 'bin/llama-server')"
  printf '  %-24s %s\n' "strata" "$(pgrep -c -f 'serve/server.py')"
  printf '  %-24s %s\n' "VRAM" "$(vram)"
  printf '  %-24s %s\n' "modelos publicados" "$(curl -sf "$BASE/v1/models" 2>/dev/null | grep -o '"id"' | wc -l)"
  printf '  %-24s %s\n' "carregado agora" "$(curl -sf "$BASE/running" 2>/dev/null | tr -d '\n' | cut -c1-64)"
}

off() {
  echo "Desligando a IA local..."
  systemctl --user stop "${UNITS[@]}" 2>/dev/null
  opencode service stop >/dev/null 2>&1
  pkill -TERM -f 'bin/llama-server' 2>/dev/null
  pkill -TERM -f 'serve/server.py.*--engine strata' 2>/dev/null
  sleep 2
  pkill -KILL -f 'bin/llama-server' 2>/dev/null
  pkill -KILL -f 'engine/strata' 2>/dev/null
  status
  echo "Pronto. Para voltar: ia on"
}

on() {
  echo "Ligando a IA local..."
  systemctl --user start "${UNITS[@]}"
  opencode service start >/dev/null 2>&1
  for _ in $(seq 30); do
    curl -sf "$BASE/v1/models" >/dev/null && break
    sleep 1
  done
  status
  echo "O primeiro modelo carrega sob demanda (35–90 s para MoE grande)."
}

case "${1:-status}" in
  off) off ;;
  on) on ;;
  status) status ;;
  *) echo "uso: ia off | ia on | ia status" >&2; exit 2 ;;
esac
