# SPEC — API de Tarefas (2 endpoints)

Fonte da verdade. Implemente EXATAMENTE isto. Nada além disto.

## Restrições técnicas
- Node 24 executando TypeScript nativo (type stripping). **Sem dependências de runtime**: só `node:http`, `node:crypto`, `node:test`, `node:assert`.
- Imports relativos SEMPRE com extensão `.ts` (ex.: `import { createTask } from '../domain/task.ts'`).
- `erasableSyntaxOnly`: proibido `enum`, `namespace` e parameter properties (`constructor(private x)`).
- Imports só de tipo usam `import type`.
- Arquitetura hexagonal: `src/domain` NÃO importa nada de `node:*` nem de `src/http`/`src/adapters`.

## Arquivos e contratos (nomes e assinaturas obrigatórios)

### `src/domain/task.ts`
```ts
export type Task = { id: string; title: string; done: boolean; createdAt: string };
export type CreateTaskResult = { ok: true; task: Task } | { ok: false; error: 'invalid_title' };
export type TaskDeps = { newId: () => string; now: () => Date };
export function createTask(input: unknown, deps: TaskDeps): CreateTaskResult;
```
Regras de `createTask`:
1. `input` precisa ser objeto não-nulo com `title` do tipo string; caso contrário → `{ ok: false, error: 'invalid_title' }`.
2. `title` é armazenado com `trim()`. Após o trim deve ter de 1 a 100 caracteres; fora disso → `invalid_title`.
3. Sucesso: `{ ok: true, task: { id: deps.newId(), title, done: false, createdAt: deps.now().toISOString() } }`.

### `src/domain/task-repository.ts`
```ts
import type { Task } from './task.ts';
export interface TaskRepository {
  save(task: Task): void;
  findById(id: string): Task | undefined;
}
```

### `src/adapters/in-memory-task-repository.ts`
```ts
export class InMemoryTaskRepository implements TaskRepository { /* Map<string, Task> */ }
```

### `src/http/app.ts`
```ts
export type AppDeps = { repo: TaskRepository; newId: () => string; now: () => Date };
export function createApp(deps: AppDeps): import('node:http').Server; // NÃO chama listen
```
Toda resposta: header `content-type: application/json` e corpo JSON.

| Método e rota | Condição | Status | Corpo |
|---|---|---|---|
| `POST /tasks` | corpo não é JSON válido | 400 | `{"error":"invalid_json"}` |
| `POST /tasks` | `createTask` falhou | 422 | `{"error":"invalid_title"}` |
| `POST /tasks` | sucesso (salva no repo) | 201 | a `Task` criada |
| `GET /tasks/:id` | existe | 200 | a `Task` |
| `GET /tasks/:id` | não existe | 404 | `{"error":"not_found"}` |
| qualquer outra rota/método | — | 404 | `{"error":"not_found"}` |

`:id` = o segmento após `/tasks/` (sem barras adicionais).

### `src/main.ts`
Compõe `InMemoryTaskRepository`, `crypto.randomUUID`, `() => new Date()`, chama `createApp` e `listen(Number(process.env.PORT ?? 3000))`.

## Critério de aceite
`pnpm test` verde (testes colocados `*.test.ts` ao lado de cada arquivo) e `pnpm typecheck` sem erros.
