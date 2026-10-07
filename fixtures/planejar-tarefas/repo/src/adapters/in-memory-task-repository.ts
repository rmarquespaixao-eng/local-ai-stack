import type { Task } from '../domain/task.ts';
import type { TaskRepository } from '../domain/task-repository.ts';

export class InMemoryTaskRepository implements TaskRepository {
  private readonly tasks = new Map<string, Task>();

  save(task: Task): void {
    this.tasks.set(task.id, task);
  }

  findById(id: string): Task | undefined {
    return this.tasks.get(id);
  }
}
