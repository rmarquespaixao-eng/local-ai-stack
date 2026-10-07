# Configs do Strata

| Arquivo | Perfil do llama-swap | O que é |
|---|---|---|
| `strata-iq2_xs.json` | `strata-flash-next` | **o principal** — ctx 131072, salva o perfil de experts aprendido |
| `strata-iq2_xs.shared-settings.json` | idem | default quando o cliente não manda `reasoning_effort` → `low` |
| `strata-iq2_xs-256k.json` | `strata-flash-next-256k` | contexto longo (262144); sem `expert_profile_save` (menos RAM em uso) |
| `strata-iq2_xs-256k.shared-settings.json` | idem | default `none` (perfil de trabalho, não de planejamento) |
| `strata-swap.sh` | todos os `strata-*` | wrapper que mata o grupo no SIGTERM |

**Não publicamos** os JSONs dos perfis `strata-swift-*` e `strata-flash-next-iq3xxs` do
`config/llama-swap.yaml`: o Swift tem um `--ple-gguf` que suspeitamos estar apontando para o shard
errado (documentado em [docs/04](../../docs/04-strata.md#46-o-que-ainda-não-sabemos)) e o IQ3_XXS não
tem veredito de qualidade medido aqui. Se for usar, copie o `strata-iq2_xs.json` e troque `--pack`,
`--native`, `--ple-gguf`, `tokenizer`, `model_name` e `log` — lembrando que no GSQ-RCO **original/Swift
o PLE é o shard 2** (shard 1 é só para o OrcaRouter).

## Antes de rodar

1. `--expert-profile` aponta para o perfil que vem no repo do Strata (`data/expert-profile.bin`).
   O `expert_profile_save` cria o **seu** perfil aprendido ao sair (e a cada 10 min) — é um retrato do
   seu corpus, fique com ele local.
2. `lib_dirs: ["/opt/rocm/lib"]` vale quando existe um ROCm de sistema. Se o `setup.sh` instalou o
   ROCm dentro do `.venv` (TheRock), copie o `lib_dirs` do JSON que ele gerou.
3. `STRATA_HIPBLASLT_TUNING` só funciona se a **versão** da tabela bater com a hipBLASLt instalada
   (aqui: `gfx1201-hipblaslt-100202.txt` com ROCm 7.2.4 / hipBLASLt 1.2.2). Não batendo, o motor usa
   hipBLAS puro (prefill bem mais lento) — calibre a sua tabela.
4. `"--vram-reserve-mib", "3072"` **não é negociável** se a placa também é a do seu desktop.
5. O bloco `sampling` **precisa existir**. Sem ele, o motor fica em greedy quando o cliente não manda
   temperatura, e o modo reasoning entra em loop de repetição.
