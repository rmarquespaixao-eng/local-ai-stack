// Teste de aceite oculto: copiado para acceptance/ do repo só depois da execução do modelo.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { AddressInfo } from 'node:net';
import { createApp } from '../src/http/app.ts';
import { InMemoryTaskRepository } from '../src/adapters/in-memory-task-repository.ts';
import { createTask } from '../src/domain/task.ts';

const FIXED = new Date('2026-01-02T03:04:05.000Z');
const deps = () => {
  let n = 0;
  return { newId: () => `id-${++n}`, now: () => FIXED };
};

async function withServer(fn: (base: string) => Promise<void>) {
  const server = createApp({ repo: new InMemoryTaskRepository(), ...deps() });
  await new Promise<void>((r) => server.listen(0, r));
  const { port } = server.address() as AddressInfo;
  try {
    await fn(`http://127.0.0.1:${port}`);
  } finally {
    await new Promise<void>((r) => server.close(() => r()));
  }
}

const post = (base: string, body: string) =>
  fetch(`${base}/tasks`, { method: 'POST', headers: { 'content-type': 'application/json' }, body });

test('domain: trim + sucesso', () => {
  const r = createTask({ title: '  comprar pão  ' }, deps());
  assert.deepEqual(r, { ok: true, task: { id: 'id-1', title: 'comprar pão', done: false, createdAt: FIXED.toISOString() } });
});

test('domain: limites de título', () => {
  for (const input of [{ title: '   ' }, { title: 'x'.repeat(101) }, { title: 1 }, null, 'abc', {}]) {
    assert.deepEqual(createTask(input, deps()), { ok: false, error: 'invalid_title' });
  }
  assert.equal(createTask({ title: 'x'.repeat(100) }, deps()).ok, true);
});

test('POST 201 + GET 200 roundtrip', () =>
  withServer(async (base) => {
    const res = await post(base, JSON.stringify({ title: ' a ' }));
    assert.equal(res.status, 201);
    assert.match(res.headers.get('content-type') ?? '', /application\/json/);
    const created = await res.json();
    assert.deepEqual(created, { id: 'id-1', title: 'a', done: false, createdAt: FIXED.toISOString() });
    const got = await fetch(`${base}/tasks/id-1`);
    assert.equal(got.status, 200);
    assert.deepEqual(await got.json(), created);
  }));

test('POST 400 invalid_json', () =>
  withServer(async (base) => {
    const res = await post(base, '{nope');
    assert.equal(res.status, 400);
    assert.deepEqual(await res.json(), { error: 'invalid_json' });
  }));

test('POST 422 invalid_title', () =>
  withServer(async (base) => {
    const res = await post(base, JSON.stringify({ title: '' }));
    assert.equal(res.status, 422);
    assert.deepEqual(await res.json(), { error: 'invalid_title' });
  }));

test('GET 404 inexistente e rotas desconhecidas', () =>
  withServer(async (base) => {
    for (const [method, path] of [['GET', '/tasks/nao-existe'], ['GET', '/'], ['DELETE', '/tasks/id-1'], ['GET', '/tasks'], ['GET', '/tasks/a/b']]) {
      const res = await fetch(`${base}${path}`, { method });
      assert.equal(res.status, 404, `${method} ${path}`);
      assert.match(res.headers.get('content-type') ?? '', /application\/json/);
      assert.deepEqual(await res.json(), { error: 'not_found' });
    }
  }));
