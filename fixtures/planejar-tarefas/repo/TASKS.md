# TASKS — execute em ordem; um commit por tarefa

Leia `SPEC.md` uma vez. Não leia `node_modules/`. Não instale dependências.
Depois de cada tarefa: `pnpm test && pnpm typecheck`, marque `[x]` aqui e faça
`git add -A && git commit -m "<mensagem indicada>"`.

- [x] T1 — `src/domain/task.ts` + `src/domain/task.test.ts` (sucesso, trim, título vazio, >100 chars, input não-objeto, title não-string). Commit: `feat(domain): create task with title validation`
- [x] T2 — `src/domain/task-repository.ts` + `src/adapters/in-memory-task-repository.ts` + teste do adapter (save/findById/inexistente). Commit: `feat(adapters): in-memory task repository`
- [x] T3 — `src/http/app.ts` + `src/http/app.test.ts` cobrindo TODAS as linhas da tabela da SPEC (subir com `listen(0)`, usar `fetch`, fechar no fim). Commit: `feat(http): POST /tasks and GET /tasks/:id`
- [x] T4 — `src/main.ts`. Commit: `feat: bootstrap server`

Pronto quando as 4 estiverem `[x]`, `pnpm test` e `pnpm typecheck` verdes e `git status` limpo.
