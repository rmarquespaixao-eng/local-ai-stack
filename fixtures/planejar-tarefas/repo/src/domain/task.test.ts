import assert from 'node:assert/strict';
import { test } from 'node:test';

import { createTask } from './task.ts';

const deps = {
  newId: () => 'id-1',
  now: () => new Date('2026-01-01T00:00:00.000Z'),
};

test('createTask: success', () => {
  const result = createTask({ title: 'write spec' }, deps);

  assert.equal(result.ok, true);
  assert.deepEqual(result.task, {
    id: 'id-1',
    title: 'write spec',
    done: false,
    createdAt: '2026-01-01T00:00:00.000Z',
  });
});

test('createTask: trims the title', () => {
  const result = createTask({ title: '  padded  ' }, deps);

  assert.equal(result.ok, true);
  assert.equal(result.task.title, 'padded');
});

test('createTask: empty title after trim', () => {
  const result = createTask({ title: '   ' }, deps);

  assert.deepEqual(result, { ok: false, error: 'invalid_title' });
});

test('createTask: title longer than 100 characters', () => {
  const result = createTask({ title: 'a'.repeat(101) }, deps);

  assert.deepEqual(result, { ok: false, error: 'invalid_title' });
});

test('createTask: title of exactly 100 characters is valid', () => {
  const result = createTask({ title: 'a'.repeat(100) }, deps);

  assert.equal(result.ok, true);
  assert.equal(result.task.title.length, 100);
});

test('createTask: input is not an object', () => {
  assert.deepEqual(createTask(null, deps), { ok: false, error: 'invalid_title' });
  assert.deepEqual(createTask('title', deps), { ok: false, error: 'invalid_title' });
  assert.deepEqual(createTask(42, deps), { ok: false, error: 'invalid_title' });
});

test('createTask: title is not a string', () => {
  assert.deepEqual(createTask({}, deps), { ok: false, error: 'invalid_title' });
  assert.deepEqual(createTask({ title: 7 }, deps), { ok: false, error: 'invalid_title' });
});
