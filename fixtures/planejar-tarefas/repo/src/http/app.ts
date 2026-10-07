import type { Server } from 'node:http';
import { createServer } from 'node:http';

import { createTask } from '../domain/task.ts';
import type { TaskRepository } from '../domain/task-repository.ts';

export type AppDeps = { repo: TaskRepository; newId: () => string; now: () => Date };

function json(res: import('node:http').ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(payload);
}

async function readBody(req: import('node:http').IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of req) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString('utf8');
}

export function createApp(deps: AppDeps): Server {
  return createServer(async (req, res) => {
    const url = new URL(req.url ?? '/', 'http://localhost');
    const path = url.pathname;

    if (req.method === 'POST' && path === '/tasks') {
      let parsed: unknown;
      try {
        parsed = JSON.parse(await readBody(req));
      } catch {
        return json(res, 400, { error: 'invalid_json' });
      }

      const result = createTask(parsed, { newId: deps.newId, now: deps.now });
      if (!result.ok) {
        return json(res, 422, { error: 'invalid_title' });
      }

      deps.repo.save(result.task);
      return json(res, 201, result.task);
    }

    if (req.method === 'GET' && path.startsWith('/tasks/')) {
      const id = path.slice('/tasks/'.length);
      const task = deps.repo.findById(id);
      if (!task) {
        return json(res, 404, { error: 'not_found' });
      }
      return json(res, 200, task);
    }

    json(res, 404, { error: 'not_found' });
  });
}
