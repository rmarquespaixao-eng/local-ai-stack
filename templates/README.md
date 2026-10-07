# Templates `.jinja` que ajustei

Chat template é o que decide se o modelo **entende** `reasoning_effort`, `enable_thinking` e o formato
de ferramenta. Um template errado normalmente não gera erro: o modelo só deixa de cooperar — e a leitura que fica é
"esse modelo é ruim". Estes são os dois que uso, com o motivo de cada mudança.

| Arquivo | Para quê | Como plugar |
|---|---|---|
| `qwen3.8-template.jinja` | Qwen3.8-27B (gsq-s / Swift 27B) no llama.cpp | `--chat-template-file /home/YOU/llm/templates/qwen3.8-template.jinja` (no meu config é o macro `qtpl`) |
| `ministral-3-relaxed.jinja` | Ministral 3 (e qualquer modelo cujo template oficial exige alternância estrita) | idem, `--chat-template-file` |
| `check_render.py` | valida os dois templates sem subir modelo | `python3 templates/check_render.py` |

## O que cada um resolve

### `qwen3.8-template.jinja` — esforço de raciocínio que não é ignorado

O template embutido no GGUF declara os esforços que aceita e levanta exceção. Quando
passei a usar variantes `low`/`medium`/`xhigh` pelo OpenCode, duas coisas apareceram:

1. O ramo `medium` tinha se perdido na cópia custom do template → o esforço virava `xhigh` em silêncio.
   Restauramos a **validação explícita** (`not in ('low','medium','xhigh') → raise_exception`) para que
   esforço errado estoure na carga em vez de virar comportamento mudo.
2. O default era `xhigh`. Para agente, deixar `low` como default economiza tempo sem perder a tarefa.
   Ficou: `reasoning_effort|default('low')`, com uma instrução de esforço injetada no system por ramo.

Também estão aqui, do template oficial: o bloco `# Tools` + o formato
`<tool_call><function=nome><parameter=chave>valor`, o papel `tool` renderizado como
`tool_response`, e — importante para quem desliga o thinking — o prefill `<think>\n\n</think>` quando
`enable_thinking:false`, que evita o modelo reasoning ficar "pensando" em texto visível.

A tabela de mapeamento que o cliente precisa respeitar (é o que o OpenCode manda via
`reasoning_effort`):

| Cliente manda | O template faz |
|---|---|
| nada / `low` | `Reasoning effort: low` (default) |
| `medium` | ramo próprio, profundidade balanceada |
| `xhigh` | ramo próprio: validar casos de borda, priorizar correção |
| `off`/`none`/`false` | no Strata vira `enable_thinking:false`; no llama.cpp, `--reasoning off` |
| outra coisa | exceção na carga (é o comportamento desejado) |

### `ministral-3-relaxed.jinja` — o HTTP 500 que não era do servidor

O template oficial do Ministral 3 fazia `raise_exception` se duas mensagens `user` aparecessem
consecutivas. Agentes mandam lembrete/system-reminder logo após uma mensagem do usuário — e o
`llama-server` respondia **500**. Removemos só essa checagem de alternância, mantendo o resto idêntico
ao original. Foi a correção que fez o modelo chegar a rodar a prova (e aí reprovar por comportamento,
não por template — ver [../docs/02-modelos.md](../docs/02-modelos.md)).

## Como validar antes de plugar no llama-swap

```bash
pip install jinja2
python3 templates/check_render.py      # 12 checagens: effort, thinking, tools, user consecutivo

# render real dentro do llama.cpp (equivalente ao que o servidor faz):
python3 -c "
from gguf import GGUFReader
r = GGUFReader('/home/YOU/models/.../arquivo.gguf')
print(bytes(r.fields['tokenizer.chat_template'].parts[0]).decode())" > embutido.jinja
diff -u embutido.jinja qwen3.8-template.jinja | head -40
```

O `diff` é o passo que evita o erro clássico: copy-paste de template de **outra** família (ou de uma
versão antiga do mesmo modelo) e depois atribuir a saída estranha ao modelo.

## Licença/attribution

Templates derivados dos embutidos nos GGUFs que baixei (Qwen/ISTA-DASLab, Unsloth/Mistral). As
licenças das weights se aplicam ao template; publico essas adaptações como *diff* de uso próprio —
se for redistribuir pesos, siga a licença do modelo correspondente.
