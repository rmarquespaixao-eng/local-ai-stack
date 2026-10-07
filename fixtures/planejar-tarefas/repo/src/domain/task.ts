export type Task = { id: string; title: string; done: boolean; createdAt: string };

export type CreateTaskResult = { ok: true; task: Task } | { ok: false; error: 'invalid_title' };

export type TaskDeps = { newId: () => string; now: () => Date };

const MAX_TITLE_LENGTH = 100;

export function createTask(input: unknown, deps: TaskDeps): CreateTaskResult {
  if (typeof input !== 'object' || input === null) {
    return { ok: false, error: 'invalid_title' };
  }

  const rawTitle = (input as { title?: unknown }).title;
  if (typeof rawTitle !== 'string') {
    return { ok: false, error: 'invalid_title' };
  }

  const title = rawTitle.trim();
  if (title.length < 1 || title.length > MAX_TITLE_LENGTH) {
    return { ok: false, error: 'invalid_title' };
  }

  return {
    ok: true,
    task: {
      id: deps.newId(),
      title,
      done: false,
      createdAt: deps.now().toISOString(),
    },
  };
}
