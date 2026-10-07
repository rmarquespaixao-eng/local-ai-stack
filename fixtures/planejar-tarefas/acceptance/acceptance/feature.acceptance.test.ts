// Teste de aceite oculto da fixture planejar-tarefas: julga o COMPORTAMENTO pedido em FEATURE.md
// (só HTTP + createApp + InMemoryTaskRepository), independente do desenho interno escolhido pelo planejador.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { AddressInfo } from 'node:net';
import { createApp } from '../src/http/app.ts';
import { InMemoryTaskRepository } from '../src/adapters/in-memory-task-repository.ts';

const FIXED = new Date('2026-01-02T03:04:05.000Z');

async function withServer(fn: (base: string) => Promise<void>) {
  let n = 0;
  const server = createApp({ repo: new InMemoryTaskRepository(), newId: () => `id-${++n}`, now: () => FIXED });
  await new Promise<void>((r) => server.listen(0, r));
  const { port } = server.address() as AddressInfo;
  try {
    await fn(`http://127.0.0.1:${port}`);
  } finally {
    await new Promise<void>((r) => server.close(() => r()));
  }
}

const json = { 'content-type': 'application/json' };
const create = async (base: string, title: string) => {
  const res = await fetch(`${base}/tasks`, { method: 'POST', headers: json, body: JSON.stringify({ title }) });
  assert.equal(res.status, 201);
  return res.json();
};
const patch = (base: string, id: string, body: string) =>
  fetch(`${base}/tasks/${id}`, { method: 'PATCH', headers: json, body });
const list = async (base: string, qs = '') => {
  const res = await fetch(`${base}/tasks${qs}`);
  return { status: res.status, ctype: res.headers.get('content-type') ?? '', body: await res.json() };
};
const task = (n: number, title: string, done = false) => ({ id: `id-${n}`, title, done, createdAt: FIXED.toISOString() });

test('regressão: POST/GET por id e 404 genérico continuam iguais', () =>
  withServer(async (base) => {
    const created = await create(base, '  a  ');
    assert.deepEqual(created, task(1, 'a'));
    const got = await fetch(`${base}/tasks/id-1`);
    assert.equal(got.status, 200);
    assert.deepEqual(await got.json(), created);
    const bad = await fetch(`${base}/tasks`, { method: 'POST', headers: json, body: '{x' });
    assert.equal(bad.status, 400);
    assert.deepEqual(await bad.json(), { error: 'invalid_json' });
    for (const [method, path] of [['GET', '/'], ['GET', '/tasks/a/b'], ['PUT', '/tasks/id-1'], ['POST', '/tasks/id-1']]) {
      const res = await fetch(`${base}${path}`, { method });
      assert.equal(res.status, 404, `${method} ${path}`);
      assert.match(res.headers.get('content-type') ?? '', /application\/json/);
      assert.deepEqual(await res.json(), { error: 'not_found' });
    }
  }));

test('GET /tasks: lista vazia e ordem de criação', () =>
  withServer(async (base) => {
    const empty = await list(base);
    assert.equal(empty.status, 200);
    assert.match(empty.ctype, /application\/json/);
    assert.deepEqual(empty.body, { items: [], total: 0 });
    await create(base, 'um');
    await create(base, 'dois');
    await create(base, 'três');
    assert.deepEqual((await list(base)).body, { items: [task(1, 'um'), task(2, 'dois'), task(3, 'três')], total: 3 });
  }));

test('GET /tasks: filtro done, paginação e total antes da paginação', () =>
  withServer(async (base) => {
    for (const t of ['a', 'b', 'c', 'd', 'e']) await create(base, t);
    for (const id of ['id-2', 'id-4']) assert.equal((await patch(base, id, JSON.stringify({ done: true }))).status, 200);
    assert.deepEqual((await list(base, '?done=true')).body, { items: [task(2, 'b', true), task(4, 'd', true)], total: 2 });
    assert.deepEqual((await list(base, '?done=false&limit=2')).body, { items: [task(1, 'a'), task(3, 'c')], total: 3 });
    assert.deepEqual((await list(base, '?limit=2&offset=2')).body, { items: [task(3, 'c'), task(4, 'd', true)], total: 5 });
    assert.deepEqual((await list(base, '?offset=10')).body, { items: [], total: 5 });
    assert.deepEqual((await list(base, '?limit=50&foo=bar')).body.total, 5);
  }));

test('GET /tasks: limite padrão 20', () =>
  withServer(async (base) => {
    for (let i = 1; i <= 25; i++) await create(base, `t${i}`);
    const r = await list(base);
    assert.equal(r.body.items.length, 20);
    assert.equal(r.body.total, 25);
    assert.deepEqual(r.body.items[19], task(20, 't20'));
  }));

test('GET /tasks: parâmetros inválidos → 400 invalid_query', () =>
  withServer(async (base) => {
    for (const qs of ['?done=talvez', '?done=1', '?limit=0', '?limit=51', '?limit=abc', '?limit=2.5', '?offset=-1', '?offset=1.5', '?offset=x']) {
      const r = await list(base, qs);
      assert.equal(r.status, 400, qs);
      assert.deepEqual(r.body, { error: 'invalid_query' }, qs);
    }
  }));

test('PATCH: sucesso em title, done e ambos; id/createdAt imutáveis', () =>
  withServer(async (base) => {
    await create(base, 'velho');
    let res = await patch(base, 'id-1', JSON.stringify({ title: '  novo  ' }));
    assert.equal(res.status, 200);
    assert.match(res.headers.get('content-type') ?? '', /application\/json/);
    assert.deepEqual(await res.json(), task(1, 'novo'));
    res = await patch(base, 'id-1', JSON.stringify({ done: true }));
    assert.deepEqual(await res.json(), task(1, 'novo', true));
    res = await patch(base, 'id-1', JSON.stringify({ title: 'x', done: false }));
    assert.deepEqual(await res.json(), task(1, 'x', false));
    const got = await fetch(`${base}/tasks/id-1`);
    assert.deepEqual(await got.json(), task(1, 'x', false));
  }));

test('PATCH: erros e ordem 400 → 404 → 422', () =>
  withServer(async (base) => {
    await create(base, 'a');
    const cases: Array<[string, string, number, string]> = [
      ['id-1', '{nope', 400, 'invalid_json'],
      ['nao-existe', '{nope', 400, 'invalid_json'],
      ['nao-existe', JSON.stringify({ title: '' }), 404, 'not_found'],
      ['nao-existe', JSON.stringify({}), 404, 'not_found'],
      ['id-1', JSON.stringify({ title: '   ' }), 422, 'invalid_title'],
      ['id-1', JSON.stringify({ title: 'x'.repeat(101) }), 422, 'invalid_title'],
      ['id-1', JSON.stringify({ title: 5 }), 422, 'invalid_title'],
      ['id-1', JSON.stringify({}), 422, 'invalid_patch'],
      ['id-1', JSON.stringify({ done: 'sim' }), 422, 'invalid_patch'],
      ['id-1', JSON.stringify({ done: true, id: 'outro' }), 422, 'invalid_patch'],
      ['id-1', JSON.stringify({ title: '', extra: 1 }), 422, 'invalid_patch'],
      ['id-1', JSON.stringify([1]), 422, 'invalid_patch'],
      ['id-1', JSON.stringify(null), 422, 'invalid_patch'],
      ['id-1', JSON.stringify('texto'), 422, 'invalid_patch'],
    ];
    for (const [id, body, status, error] of cases) {
      const res = await patch(base, id, body);
      assert.equal(res.status, status, `${id} ${body}`);
      assert.deepEqual(await res.json(), { error }, `${id} ${body}`);
    }
    const got = await fetch(`${base}/tasks/id-1`);
    assert.deepEqual(await got.json(), task(1, 'a'), 'erros não alteram a tarefa');
  }));

test('DELETE: 204 sem corpo, some do GET e da lista; 404 se não existe', () =>
  withServer(async (base) => {
    await create(base, 'a');
    await create(base, 'b');
    const res = await fetch(`${base}/tasks/id-1`, { method: 'DELETE' });
    assert.equal(res.status, 204);
    assert.equal(await res.text(), '');
    assert.equal((await fetch(`${base}/tasks/id-1`)).status, 404);
    assert.deepEqual((await list(base)).body, { items: [task(2, 'b')], total: 1 });
    const again = await fetch(`${base}/tasks/id-1`, { method: 'DELETE' });
    assert.equal(again.status, 404);
    assert.deepEqual(await again.json(), { error: 'not_found' });
  }));
