#!/usr/bin/env python3
"""Espelha os modelos do llama-swap no opencode.json, sem pisar nas suas opções.

    sync-opencode-models.py [--config PATH] [--base-url URL] [--dry-run]

Por que existe: com 16 perfis, manter a lista do cliente na mão é garantia de divergência (nome
errado, contexto anunciado diferente do `-c` real, variante esquecida).

O que ele faz:
  - lê GET {base_url}/models (qualquer servidor OpenAI-compatível local);
  - adiciona o que apareceu, remove o que sumiu;
  - PRESERVA o que é seu por modelo: limit, variants, cost, description;
  - se a fonte estiver fora do ar, mantém a lista atual (não apaga tudo por um timeout);
  - só reescreve se algo mudou, com backup `.bak-sync-latest` e escrita atômica.

Esta é a versão reduzida, só com a parte local, do script que rodo em produção (que também sincroniza)
provedores de nuvem, e publicar isso aqui incentivaria gente a meter `apiKey` dentro de um config
versionado. Se você precisa disso, use variáveis de ambiente no config do cliente.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
import urllib.error
import urllib.request
from pathlib import Path

PROVIDER = "llama-cpp"
DEFAULT_LIMIT = {"context": 131072, "output": 8192}
TIMEOUT = 20


def model_ids(base_url: str) -> list[str]:
    req = urllib.request.Request(
        f"{base_url.rstrip('/')}/models", headers={"User-Agent": "sync-opencode-models"}
    )
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return [m["id"] for m in json.loads(resp.read())["data"]]


def rebuild(current: dict, ids: list[str]) -> dict:
    out: dict[str, dict] = {}
    for mid in ids:
        entry = dict(current.get(mid) or {})
        entry.setdefault("name", mid)
        entry.setdefault("limit", dict(DEFAULT_LIMIT))
        out[mid] = entry
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--config", default=str(Path.home() / ".config/opencode/opencode.json"))
    ap.add_argument("--base-url", default="http://127.0.0.1:8082/v1")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    path = Path(args.config)
    if not path.exists():
        print(f"config não encontrado: {path}", file=sys.stderr)
        return 2

    config = json.loads(path.read_text())
    block = config.setdefault("provider", {}).setdefault(PROVIDER, {})
    current = block.get("models", {})

    try:
        ids = model_ids(args.base_url)
    except (urllib.error.URLError, TimeoutError, KeyError, json.JSONDecodeError) as exc:
        print(f"[{PROVIDER}] fonte indisponível, mantendo a lista atual: {exc!r}", file=sys.stderr)
        return 1

    new = rebuild(current, ids)
    added, removed = sorted(set(new) - set(current)), sorted(set(current) - set(new))
    changed = sorted(k for k in set(new) & set(current) if new[k] != current[k])
    if not (added or removed or changed):
        print("sem mudanças")
        return 0

    print(f"[{PROVIDER}] +{added or '-'} -{removed or '-'} ~{changed or '-'}")
    if args.dry_run:
        print("(dry-run: nada gravado)")
        return 0

    block["models"] = new
    shutil.copy2(path, path.with_name(path.name + ".bak-sync-latest"))
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(config, indent=2, ensure_ascii=False) + "\n")
    tmp.replace(path)
    print(f"gravado {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
