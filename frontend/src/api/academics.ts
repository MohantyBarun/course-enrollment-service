import { apiRequest, newIdempotencyKey } from './client';
import type {
  AcademicRecord, AdminDashboard, Announcement, AttendanceSheet, Course, CourseRequest, GradeLabel,
  LoginResponse, MyEnrollment, RosterEntry, StudentDashboard,
} from './types';

export const coursesApi = {
  list: (filters: { search?: string; department?: string; term?: string } = {}) =>
    apiRequest<Course[]>('/courses', { query: filters }),
  get: (id: number) => apiRequest<Course>(`/courses/${id}`),
  create: (course: CourseRequest) => apiRequest<Course>('/courses', { method: 'POST', body: course }),
  update: (id: number, course: CourseRequest) => apiRequest<Course>(`/courses/${id}`, { method: 'PUT', body: course }),
  remove: (id: number) => apiRequest<void>(`/courses/${id}`, { method: 'DELETE' }),
  roster: (id: number) => apiRequest<RosterEntry[]>(`/courses/${id}/roster`),
  attendanceSheet: (id: number, date: string) =>
    apiRequest<AttendanceSheet>(`/courses/${id}/attendance`, { query: { date } }),
  recordAttendance: (id: number, date: string, records: { enrollmentId: number; present: boolean }[]) =>
    apiRequest<AttendanceSheet>(`/courses/${id}/attendance`, { method: 'POST', body: { date, records } }),
  enroll: (id: number) =>
    apiRequest<MyEnrollment>(`/courses/${id}/enroll`, { method: 'POST', idempotencyKey: newIdempotencyKey() }),
  drop: (id: number) => apiRequest<void>(`/courses/${id}/enroll`, { method: 'DELETE' }),
};

export const academicsApi = {
  myRecord: () => apiRequest<AcademicRecord>('/students/me/academics'),
  recordOf: (studentId: number) => apiRequest<AcademicRecord>(`/students/${studentId}/academics`),
  assignGrade: (enrollmentId: number, grade: GradeLabel) =>
    apiRequest<RosterEntry>(`/enrollments/${enrollmentId}/grade`, { method: 'PUT', body: { grade } }),
  adminDashboard: () => apiRequest<AdminDashboard>('/dashboard/admin'),
  studentDashboard: () => apiRequest<StudentDashboard>('/dashboard/student'),
};

export const announcementsApi = {
  list: () => apiRequest<Announcement[]>('/announcements'),
  create: (a: { title: string; body: string; audienceDepartment?: string }) =>
    apiRequest<Announcement>('/announcements', { method: 'POST', body: a }),
  remove: (id: number) => apiRequest<void>(`/announcements/${id}`, { method: 'DELETE' }),
};

export const demoApi = {
  status: () => apiRequest<{ enabled: boolean }>('/auth/demo', { auth: false }),
  login: (role: 'ADMIN' | 'STUDENT') =>
    apiRequest<LoginResponse>('/auth/demo-login', { method: 'POST', body: { role }, auth: false }),
};
