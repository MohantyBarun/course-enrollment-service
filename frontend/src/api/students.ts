import { apiRequest, newIdempotencyKey } from './client';
import type { Page, Student, StudentQuery, StudentRequest } from './types';

export const studentsApi = {
  list: (query: StudentQuery) =>
    apiRequest<Page<Student>>('/students', { query: { ...query } }),

  get: (id: number) => apiRequest<Student>(`/students/${id}`),

  me: () => apiRequest<Student>('/students/me'),

  create: (student: StudentRequest) =>
    apiRequest<Student>('/students', { method: 'POST', body: student, idempotencyKey: newIdempotencyKey() }),

  update: (id: number, student: StudentRequest) =>
    apiRequest<Student>(`/students/${id}`, { method: 'PUT', body: student }),

  remove: (id: number) => apiRequest<void>(`/students/${id}`, { method: 'DELETE' }),
};
