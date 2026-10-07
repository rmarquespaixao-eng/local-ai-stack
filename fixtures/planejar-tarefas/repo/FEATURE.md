# Pedido de feature — gerenciar tarefas (listar, editar, concluir, excluir)

> Escrito pelo dono do produto. O que vale é o comportamento descrito aqui.
> `SPEC.md` e `TASKS.md` atuais descrevem a primeira versão (já entregue e funcionando).

## Contexto
Hoje a API só cria uma tarefa (`POST /tasks`) e busca uma por id (`GET /tasks/:id`). Os clientes
precisam ver a lista, corrigir o título, marcar como concluída e apagar tarefas.

## O que precisa existir

### 1. Listar tarefas — `GET /tasks`
- Responde `200` com `{ "items": [ ...tarefas ], "total": <número> }`.
- Ordem: a ordem em que as tarefas foram criadas (a mais antiga primeiro).
- Filtro opcional `?done=true` ou `?done=false`.
- Paginação opcional: `?limit=` (inteiro de 1 a 50, padrão 20) e `?offset=` (inteiro ≥ 0, padrão 0).
- `total` é a quantidade de tarefas **depois do filtro e antes da paginação**.
- Qualquer valor inválido nesses parâmetros (ex.: `done=talvez`, `limit=0`, `limit=51`, `limit=abc`,
  `offset=-1`, `offset=1.5`) → `400` com `{ "error": "invalid_query" }`. Parâmetros desconhecidos são ignorados.
- Atenção: hoje `GET /tasks` responde 404 — isso deixa de valer.

### 2. Editar / concluir — `PATCH /tasks/:id`
- Corpo JSON com `title` e/ou `done`. Pelo menos um dos dois.
- `title` segue as mesmas regras da criação (trim, 1 a 100 caracteres) → se inválido, `422 { "error": "invalid_title" }`.
- `done` precisa ser booleano.
- Corpo que não é objeto, sem nenhum dos dois campos, com `done` não booleano ou com qualquer outro
  campo além de `title`/`done` → `422 { "error": "invalid_patch" }`.
  (Se o corpo tiver campo extra **e** título inválido, a resposta é `invalid_patch`.)
- Corpo que não é JSON → `400 { "error": "invalid_json" }`.
- Tarefa inexistente → `404 { "error": "not_found" }`.
- Ordem das verificações: JSON inválido (400) → tarefa inexistente (404) → validação (422).
- Sucesso → `200` com a tarefa atualizada. `id` e `createdAt` nunca mudam.

### 3. Excluir — `DELETE /tasks/:id`
- Existe → `204` **sem corpo** (única resposta da API sem corpo JSON).
- Não existe → `404 { "error": "not_found" }`.
- Depois de excluída, `GET /tasks/:id` dá 404 e ela some da lista.

## O que NÃO pode mudar
- Tudo o que já funciona: `POST /tasks`, `GET /tasks/:id`, os erros existentes, `404 not_found` para rotas
  desconhecidas, `content-type: application/json` em toda resposta com corpo.
- A assinatura `createApp(deps)` com `deps = { repo, newId, now }`, e `new InMemoryTaskRepository()` sem argumentos
  (outros times usam assim).
- As restrições técnicas da SPEC atual (Node 24 TS nativo, sem dependências, imports `.ts`, hexagonal etc.).
