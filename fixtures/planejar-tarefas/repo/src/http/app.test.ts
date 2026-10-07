import assert from 'node:assert/strict';
import { test } from 'node:test';

import { InMemoryTaskRepository } from '../adapters/in-memory-task-repository.ts';
import { createApp } from './app.ts';

async function withServer(callback: (baseUrl: string) => Promise<void>): Promise<void> {
  const server = createApp({
    repo: new InMemoryTaskRepository(),
    newId: () => 'id-1',
    now: () => new Date('2026-01-01T00:00:00.000Z'),
  });

  await new Promise<void>((resolve) => server.listen(0, resolve));
  const address = server.address();
  const port = typeof address === 'object' && address !== null ? address.port : 0;

  try {
    await callback(`http://127.0.0.1:${port}`);
  } finally {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  }
}

test('POST /tasks: invalid JSON body returns 400', async () => {
  await withServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/tasks`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '{not json',
    });

    assert.equal(res.status, 400);
    assert.equal(res.headers.get('content-type'), 'application/json');
    assert.deepEqual(await res.json(), { error: 'invalid_json' });
  });
});

test('POST /tasks: invalid title returns 422', async () => {
  await withServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/tasks`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ title: '   ' }),
    });

    assert.equal(res.status, 422);
    assert.equal(res.headers.get('content-type'), 'application/json');
    assert.deepEqual(await res.json(), { error: 'invalid_title' });
  });
});

test('POST /tasks: success returns 201 and the created task', async () => {
  await withServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/tasks`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ title: '  write spec  ' }),
    });

    assert.equal(res.status, 201);
    assert.equal(res.headers.get('content-type'), 'application/json');
    assert.deepEqual(await res.json(), {
      id: 'id-1',
      title: 'write spec',
      done: false,
      createdAt: '2026-01-01T00:00:00.000Z',
    });
  });
});

test('GET /tasks/:id: existing task returns 200', async () => {
  await withServer(async (baseUrl) => {
    const createRes = await fetch(`${baseUrl}/tasks`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ title: 'write spec' }),
    });
    assert.equal(createRes.status, 201);

    const res = await fetch(`${baseUrl}/tasks/id-1`);

    assert.equal(res.status, 200);
    assert.equal(res.headers.get('content-type'), 'application/json');
    assert.deepEqual(await res.json(), {
      id: 'id-1',
      title: 'write spec',
      done: false,
      createdAt: '2026-01-01T00:00:00.000Z',
    });
  });
});

test('GET /tasks/:id: missing task returns 404', async () => {
  await withServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/tasks/missing`);

    assert.equal(res.status, 404);
    assert.equal(res.headers.get('content-type'), 'application/json');
    assert.deepEqual(await res.json(), { error: 'not_found' });
  });
});

test('any other route/method returns 404', async () => {
  await withServer(async (baseUrl) => {
    const res = await fetch(`${baseUrl}/tasks`);

    assert.equal(res.status, 404);
    assert.equal(res.headers.get('content-type'), 'application/json');
    assert.deepEqual(await res.json(), { error: 'not_found' });
  });
});
