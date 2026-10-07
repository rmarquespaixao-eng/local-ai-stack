import assert from 'node:assert/strict';
import { test } from 'node:test';

import type { Task } from '../domain/task.ts';
import { InMemoryTaskRepository } from './in-memory-task-repository.ts';

const task: Task = {
  id: 'id-1',
  title: 'write spec',
  done: false,
  createdAt: '2026-01-01T00:00:00.000Z',
};

test('InMemoryTaskRepository: save and findById', () => {
  const repo = new InMemoryTaskRepository();

  repo.save(task);

  assert.equal(repo.findById('id-1'), task);
});

test('InMemoryTaskRepository: findById of a missing id returns undefined', () => {
  const repo = new InMemoryTaskRepository();

  assert.equal(repo.findById('missing'), undefined);
});
