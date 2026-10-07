#!/usr/bin/env bash
# verify.sh — roda o aceite oculto contra um repositório já trabalhado pelo modelo.
#
#   ./fixtures/verify.sh api-tarefas /tmp/run-api
#   ./fixtures/verify.sh api-tarefas /tmp/run-api --dry-run
#
# Copia fixtures/<nome>/acceptance para dentro do repo, roda o `verify_cmd` do fixture.json, mostra o
# resultado cru e limpa o aceite do repositório depois. Também avisa se o modelo mexeu em arquivo do
# `protect` (o que costuma invalidar o run: "consertou" o build em vez de fazer a tarefa).
set -uo pipefail

FIXTURE="${1:?uso: verify.sh <fixture> <repo-do-modelo> [--dry-run]}"
REPO="${2:?falta o diretório do repo}"
DRY="${3:-}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$HERE/$FIXTURE"
ACC="$SRC/acceptance"

[[ -d "$REPO/.git" ]] || { echo "não é um repositório git: $REPO" >&2; exit 2; }
[[ -d "$ACC" ]] || { echo "não achei o aceite em $ACC" >&2; exit 2; }
[[ -f "$SRC/fixture.json" ]] || { echo "não achei $SRC/fixture.json" >&2; exit 2; }

# duas linhas: 1) verify_cmd  2) lista de protegidos (separada por espaço)
META=$(python3 - "$SRC/fixture.json" <<'PY'
import json, sys
f = json.load(open(sys.argv[1]))
print(f.get("verify_cmd", ""))
print(" ".join(f.get("protect", [])) or "-")
PY
)
CMD=$(printf '%s\n' "$META" | sed -n 1p)
PROTECT=$(printf '%s\n' "$META" | sed -n 2p)

# base = primeiro commit do repo criado pelo bootstrap.sh (o estado do fixture)
BASE=$(git -C "$REPO" rev-list --max-parents=0 HEAD 2>/dev/null | tail -1)
if [[ -n "$BASE" && "$PROTECT" != "-" ]]; then
  for altered in $(git -C "$REPO" diff --name-only "$BASE" HEAD 2>/dev/null); do
    for pattern in $PROTECT; do
      [[ $altered == $pattern ]] && echo "⚠️  o modelo alterou arquivo protegido: $altered (veja se o run ainda vale)"
    done
  done
fi

echo "== aceite: $(basename "$FIXTURE") → $REPO"
echo "== comando: $CMD"
if [[ "$DRY" == "--dry-run" ]]; then
  echo "(dry-run: nada executado)"
  exit 0
fi

MANIFEST=$(mktemp)
( cd "$ACC" && find . -type f ! -name '.gitignore' -printf '%p\n' | sed 's|^\./||' > "$MANIFEST" )
cp -r "$ACC"/. "$REPO/"

cd "$REPO"
set +e
eval "$CMD"
rc=$?
set -e
echo "== exit code do aceite: $rc"

# devolve o repo ao estado sem o aceite: apaga exatamente os arquivos copiados (e os diretórios
# vazios que sobram), sem tocar em nada que o modelo criou
while IFS= read -r rel; do
  rm -f "$REPO/$rel"
  d=$(dirname "$REPO/$rel")
  while [[ "$d" == "$REPO"/* ]]; do
    rmdir "$d" 2>/dev/null || break
    d=$(dirname "$d")
  done
done < "$MANIFEST"
rm -f "$MANIFEST"

exit $rc
