#!/usr/bin/env python3
"""Renderiza os templates .jinja deste repo com Jinja2, sem subir modelo.

    python3 templates/check_render.py

Cobre os pontos que nos quebraram e por isso foram consertados:
- reasoning_effort validado (low|medium|xhigh), default low, valor inválido = exceção;
- enable_thinking:false gera o prefill que fecha o raciocínio antes da resposta;
- tools renderizam o bloco de ferramentas e o formato de tool_call;
- user consecutivo não derruba o template do Ministral (o oficial devolvia HTTP 500).

O llama.cpp renderiza o template com Jinja também, então o que falha aqui falha na carga.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

try:
    from jinja2 import Environment
except ImportError:  # pragma: no cover
    sys.exit("dependência ausente: pip install jinja2")

HERE = Path(__file__).parent
FALHAS: list[str] = []


class TemplateError(Exception):
    """Equivalente ao raise_exception do chat runtime do llama.cpp."""


def raise_exception(message: str):
    raise TemplateError(message)


def render(name: str, **context) -> str:
    env = Environment()
    env.globals["raise_exception"] = raise_exception
    return env.from_string((HERE / name).read_text()).render(**context)


def check(nome: str, ok: bool, detalhe: str = "") -> None:
    print(("  ok    " if ok else "  FALHA ") + nome + ("" if ok else f"  — {detalhe}"))
    if not ok:
        FALHAS.append(nome)


def qwen(tools=None, effort=None, thinking=None, assistant_calls=None):
    messages = [
        {"role": "system", "content": "Você é um agente."},
        {"role": "user", "content": "crie a rota"},
    ]
    if assistant_calls is not None:
        messages.append({"role": "assistant", "content": "", "tool_calls": assistant_calls})
    ctx = {"messages": messages, "add_generation_prompt": True}
    if tools is not None:
        ctx["tools"] = tools
    if effort is not None:
        ctx["reasoning_effort"] = effort
    if thinking is not None:
        ctx["enable_thinking"] = thinking
    return ctx


TOOLS = [{"type": "function", "function": {"name": "shell", "parameters": {"type": "object"}}}]

OPEN = "<" + "think>"
CLOSE = "<" + "/think>"
FN = "<" + "function=shell>"
PARAM = "<" + "parameter=cmd>"
RES = "<" + "tool_response>"


def main() -> int:
    print("== qwen3.8-template.jinja ==")
    out = render("qwen3.8-template.jinja", **qwen())
    check("sem effort declarado -> default low", "Reasoning effort: low" in out, out[:150])

    for effort in ("low", "medium", "xhigh"):
        out = render("qwen3.8-template.jinja", **qwen(effort=effort))
        check(f"effort {effort} tem ramo proprio", f"Reasoning effort: {effort}" in out)

    try:
        render("qwen3.8-template.jinja", **qwen(effort="alta"))
        check("effort invalido levanta excecao", False, "renderizou sem erro")
    except TemplateError as exc:
        check("effort invalido levanta excecao", "reasoning effort" in str(exc).lower(), str(exc))

    out = render("qwen3.8-template.jinja", **qwen(thinking=False))
    check("enable_thinking=false -> raciocinio ja fechado no prefill", OPEN + chr(10) + chr(10) + CLOSE in out, out[-90:])

    out = render("qwen3.8-template.jinja", **qwen(thinking=True))
    check("enable_thinking=true -> abre o bloco de raciocinio", out.rstrip().endswith(OPEN), out[-90:])

    out = render("qwen3.8-template.jinja", **qwen(tools=TOOLS))
    check("tools -> bloco de ferramentas com a funcao", "# Tools" in out and "shell" in out)

    out = render("qwen3.8-template.jinja", **qwen(
        tools=TOOLS, assistant_calls=[{"function": {"name": "shell", "arguments": {"cmd": "ls"}}}]))
    check("tool_call vira function/parameter", FN in out and PARAM in out, out[-200:])

    out = render("qwen3.8-template.jinja", **qwen())
    # role=tool é renderizado como tool_response dentro de uma mensagem user
    ctx = qwen()
    ctx["messages"].append({"role": "tool", "content": "arquivos", "tool_call_id": "1"})
    out = render("qwen3.8-template.jinja", **ctx)
    check("mensagens tool viram tool_response", RES in out, out[-200:])

    print("== ministral-3-relaxed.jinja ==")
    two_users = [
        {"role": "user", "content": "primeira"},
        {"role": "user", "content": "seguida (lembrete do harness)"},
        {"role": "assistant", "content": "ok"},
    ]
    try:
        out = render("ministral-3-relaxed.jinja", messages=two_users, add_generation_prompt=True)
        check("user consecutivo renderiza (o oficial dava 500)", "primeira" in out and "seguida" in out)
    except TemplateError as exc:
        check("user consecutivo renderiza (o oficial dava 500)", False, str(exc))

    one = [{"role": "user", "content": "oi"}]
    out = render("ministral-3-relaxed.jinja", messages=one, add_generation_prompt=True)
    check("case simples: system default + [INST]oi[/INST]", "[SYSTEM_PROMPT]" in out and "[INST]oi[/INST]" in out, out[-60:])

    print("\n" + ("TUDO OK" if not FALHAS else f"{len(FALHAS)} falha(s): {FALHAS}"))
    return 0 if not FALHAS else 1


if __name__ == "__main__":
    sys.exit(main())
