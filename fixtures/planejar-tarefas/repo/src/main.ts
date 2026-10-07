import { randomUUID } from 'node:crypto';

import { InMemoryTaskRepository } from './adapters/in-memory-task-repository.ts';
import { createApp } from './http/app.ts';

const app = createApp({
  repo: new InMemoryTaskRepository(),
  newId: () => randomUUID(),
  now: () => new Date(),
});

const port = Number(process.env.PORT ?? 3000);
app.listen(port);
