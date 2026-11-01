// Mirrors the Spring Boot DTOs

export type Role = 'ROLE_ADMIN' | 'ROLE_SUPER_ADMIN' | 'ROLE_STUDENT';

export interface LoginResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  role: Role;
  email: string;
  isFirstTimeLogin: boolean;
}

export interface RefreshResponse {
  accessToken: string;
}

export interface Student {
  id: number;
  name: string;
  email: string;
  department: string;
  branch: string;
  dateOfBirth: string; // ISO date (yyyy-mm-dd)
  presentAddress: string | null;
  permanentAddress: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  /** Only present in the response to creating a student. */
  temporaryPassword?: string;
}

export interface StudentRequest {
  name: string;
  email: string;
  department: string;
  branch: string;
  dateOfBirth: string;
  presentAddress?: string;
  permanentAddress?: string;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export type SortField = 'id' | 'name' | 'email' | 'department' | 'branch' | 'dateOfBirth' | 'createdAt';
export type SortDirection = 'asc' | 'desc';

export interface StudentQuery {
  search?: string;
  department?: string;
  branch?: string;
  page: number;
  size: number;
  sortBy: SortField;
  direction: SortDirection;
}

// ---------------------------------------------------------------- academics

export type EnrollmentStatus = 'ENROLLED' | 'DROPPED' | 'COMPLETED';
export type GradeLabel = 'O' | 'A+' | 'A' | 'B+' | 'B' | 'C' | 'P' | 'F';
export const GRADES: { label: GradeLabel; points: number; description: string }[] = [
  { label: 'O', points: 10, description: 'Outstanding' },
  { label: 'A+', points: 9, description: 'Excellent' },
  { label: 'A', points: 8, description: 'Very good' },
  { label: 'B+', points: 7, description: 'Good' },
  { label: 'B', points: 6, description: 'Above average' },
  { label: 'C', points: 5, description: 'Average' },
  { label: 'P', points: 4, description: 'Pass' },
  { label: 'F', points: 0, description: 'Fail' },
];

export interface Course {
  id: number;
  code: string;
  title: string;
  description: string | null;
  credits: number;
  department: string;
  term: string;
  capacity: number;
  enrolledCount: number;
  seatsLeft: number;
  instructor: string | null;
  schedule: string | null;
  enrollmentOpen: boolean;
}

export interface CourseRequest {
  code: string;
  title: string;
  description?: string;
  credits: number;
  department: string;
  term: string;
  capacity: number;
  instructor?: string;
  schedule?: string;
  enrollmentOpen: boolean;
}

export interface CourseSummary {
  id: number;
  code: string;
  title: string;
  credits: number;
  term: string;
  instructor: string | null;
  schedule: string | null;
}

export interface AttendanceSummary {
  classesHeld: number;
  classesAttended: number;
  percentage: number | null;
  belowMinimum?: boolean;
}

export interface MyEnrollment {
  id: number;
  course: CourseSummary;
  status: EnrollmentStatus;
  grade: GradeLabel | null;
  gradePoints: number | null;
  enrolledAt: string | null;
  attendance: AttendanceSummary;
}

export interface TermResult {
  term: string;
  sgpa: number | null;
  credits: number;
}

export interface AcademicRecord {
  cgpa: number | null;
  completedCredits: number;
  terms: TermResult[];
  enrollments: MyEnrollment[];
}

export interface RosterEntry {
  enrollmentId: number;
  studentId: number;
  studentName: string;
  studentEmail: string;
  branch: string;
  status: EnrollmentStatus;
  grade: GradeLabel | null;
  attendance: AttendanceSummary;
}

export interface AttendanceSheetRow {
  enrollmentId: number;
  studentId: number;
  studentName: string;
  branch: string;
  present: boolean | null;
}

export interface AttendanceSheet {
  date: string;
  rows: AttendanceSheetRow[];
  recordedDates: string[];
}

export interface Announcement {
  id: number;
  title: string;
  body: string;
  audienceDepartment: string | null;
  authorName: string | null;
  createdAt: string | null;
}

export interface AdminDashboard {
  totalStudents: number;
  totalCourses: number;
  activeEnrollments: number;
  averageAttendance: number | null;
  lowAttendance: { studentId: number; studentName: string; courseId: number; courseCode: string; percentage: number }[];
  courseFill: { courseId: number; code: string; title: string; enrolled: number; capacity: number }[];
  studentsByDepartment: { department: string; students: number }[];
  recentAnnouncements: Announcement[];
}

export interface StudentDashboard {
  name: string;
  department: string;
  branch: string;
  cgpa: number | null;
  completedCredits: number;
  currentCredits: number;
  overallAttendance: number | null;
  coursesBelowAttendance: number;
  currentCourses: MyEnrollment[];
  announcements: Announcement[];
}

export type NotificationStatus = 'QUEUED' | 'RETRYING' | 'IN_BROKER' | 'SENT' | 'LOGGED' | 'PROCESSED' | 'FAILED';

export interface NotificationEntry {
  id: string;
  eventType: string;
  label: string;
  recipient: string | null;
  subject: string;
  preview: string | null;
  status: NotificationStatus;
  detail: string | null;
  attempts: number;
  createdAt: string;
  handledAt: string | null;
  nextAttemptAt: string | null;
}

export interface NotificationFeed {
  emailMode: string;
  transport: string;
  /** Masked; empty when real email isn't restricted to an allowlist. */
  allowedRecipients: string[];
  entries: NotificationEntry[];
}

export interface SystemSnapshot {
  generatedAt: string;
  infrastructure: {
    cache: string; rateLimiter: string; messaging: string; email: string; tracingExport: boolean; demoMode: boolean;
  };
  http: { requests: number; clientErrors: number; serverErrors: number; meanMs: number; maxMs: number };
  caches: { name: string; hits: number; misses: number; hitRate: number | null; size: number | null }[];
  outbox: {
    pending: number; failed: number; published: number; relayedPublished: number; relayedRetries: number; relayedFailed: number;
  };
  rateLimits: { rejectedByRule: Record<string, number>; limitsPerMinute: Record<string, number>; backendErrors: number };
  circuitBreakers: { name: string; state: string; failureRate: number | null; bufferedCalls: number; notPermittedCalls: number }[];
  operations: { name: string; calls: number; failures: number; meanMs: number }[];
  jvm: { uptimeSeconds: number; heapUsedMb: number; heapMaxMb: number; threads: number };
}
