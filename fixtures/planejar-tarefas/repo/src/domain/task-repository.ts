import type { Task } from './task.ts';

export interface TaskRepository {
  save(task: Task): void;
  findById(id: string): Task | undefined;
}
