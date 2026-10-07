#!/usr/bin/env bash
# bootstrap.sh — transforma fixtures/<nome>/repo em um repositório git pronto para o teste.
#
#   ./fixtures/bootstrap.sh api-tarefas            # cria em /tmp/fixt/<nome>
#   ./fixtures/bootstrap.sh api-tarefas ~/runs/ts  # cria no caminho indicado
#
# O repositório fica na branch `fixture` (é o estado base: SPEC.md + TASKS.md + build, sem a
# implementação). O modelo trabalha em cima dela; o aceite oculto só entra no verify.sh.
set -euo pipefail

FIXTURE="${1:?uso: bootstrap.sh <nome-do-fixture> [dir]}"
NAME="$(basename "$FIXTURE")"
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$FIXTURE"
DST="${2:-/tmp/fixt/$NAME}"

[[ -d "$SRC/repo" ]] || { echo "não achei $SRC/repo" >&2; exit 2; }
[[ -f "$SRC/fixture.json" ]] || echo "aviso: sem fixture.json (não sei o comando de verificação)" >&2

rm -rf "$DST"
mkdir -p "$(dirname "$DST")"
cp -r "$SRC/repo" "$DST"
rm -rf "$DST/.git"
git -C "$DST" init -q -b fixture
git -C "$DST" -c user.name=bench -c user.email=bench@local add -A
git -C "$DST" -c user.name=bench -c user.email=bench@local commit -qm "fixture: estado base"

echo "pronto: $DST (branch fixture, $(git -C "$DST" ls-files | wc -l) arquivos)"
echo "aceite oculto em: $SRC/acceptance (não copie para o modelo)"
echo "rodar o teste:    $(basename "${BASH_SOURCE[0]}")/../fixtures/verify.sh $FIXTURE $DST"
