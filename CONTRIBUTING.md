# Contribuindo

Este repo é o registro da minha configuração — escrito para ser útil a quem tem hardware parecido, não
como especificação universal. Mudanças são bem-vindas com um critério: número medido em vez de opinião.

## O que ajuda

- **Um hardware diferente com os mesmos arquivos.** "RX 7900 XTX, 64 GB: com `--fit-target 1536` e
  IQ3_XXS deu X t/s" vale mais que qualquer sugestão genérica. Diga a placa, a RAM, a versão do
  motor e o comando exato.
- **Um reprovado novo.** Se você rodou a escada ([docs/08](docs/08-avaliacao.md)) e um modelo falhou,
  mande o **modo de falha** (loop? parou sem chamar ferramenta? quebrou contrato? recusou?). É a parte com mais valor para quem vai testar depois.
- **Um defeito que não está em [docs/07](docs/07-problemas.md)**, com o diagnóstico de como você
  chegou à causa.
- **Configurações que não funcionaram**, com o motivo. Um "não faça isso" com explicação economiza
  noites.

## O que não entra

- Pesos, `.gguf`, logs de sessão, perfis de experts aprendidos (retrato do seu corpus) ou qualquer
  caminho/hostname real — use `/home/YOU`.
- Número de benchmark sem comando reproduzível, versão do motor e hardware.
- "Deveria usar tal modelo" sem teste de agente. HumanEval não mede o eixo que importa aqui.

## Formato

- Docs em **português**, `kebab-case` nos arquivos, uma tabela por assunto (o estilo do repo é
  denso: fato + número + condição).
- Configs em `config/` são cópias **sanitizadas** dos arquivos que rodam aqui
  (`/home/YOU`, sem perfis comentados). Publique só o que você realmente roda: config de exemplo
  divergente do comportamento real é pior do que não publicar. Se mexer num perfil, ajuste também o
  comentário do macro e a tabela de [docs/02](docs/02-modelos.md).
- Commits atômicos, Conventional Commits (`docs(modelos):`, `fix(config):`, `chore(scripts):`).

## Valide antes de abrir PR

```bash
python3 -c "import yaml;yaml.safe_load(open('config/llama-swap.yaml'))"
python3 -c "import json,glob;[json.load(open(f)) for f in glob.glob('config/**/*.json',recursive=True)]"
llama-swap -config config/llama-swap.yaml -validate      # 'config is valid: N model(s)'
bash -n scripts/*.sh
python3 -m py_compile scripts/sync-opencode-models.py
grep -rnE '/home/[^Y]|/mnt/[a-z]+|sk-[a-zA-Z0-9_]{20}|gho_|api[Kk]ey *:' . \\
  --exclude-dir=.git --exclude=CONTRIBUTING.md          # o esperado é saída vazia
```
