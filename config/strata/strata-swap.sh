#!/usr/bin/env bash
# Sobe o servidor do Strata para o llama-swap (a porta vem do llama-swap) e, ao receber TERM/INT,
# encerra o grupo inteiro (servidor Python + motor `strata`). Um motor órfão seguraria ~14 GB de VRAM.
# uso: strata-swap.sh <config.json> <porta>
set -u
STRATA_HOME="${STRATA_HOME:-$HOME/llm/Strata}"
cd "$STRATA_HOME" || exit 1
setsid "$STRATA_HOME/.venv/bin/python" "$STRATA_HOME/serve/server.py" \
  --engine strata --config "$1" --port "$2" &
CHILD=$!
stop() {
  kill -TERM -- -"$CHILD" 2>/dev/null
  for _ in $(seq 20); do
    kill -0 -- -"$CHILD" 2>/dev/null || exit 0
    sleep 0.5
  done
  kill -KILL -- -"$CHILD" 2>/dev/null
  exit 0
}
trap stop TERM INT HUP
wait "$CHILD"
