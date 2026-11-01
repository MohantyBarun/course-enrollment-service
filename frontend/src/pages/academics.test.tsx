import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import type { AcademicRecord, Course, RosterEntry, StudentDashboard } from '../api/types';
import { fakeJwt, jsonResponse, mockFetch, renderAt, signIn } from '../test/helpers';
import { AnnouncementsPage } from './AnnouncementsPage';
import { CourseDetailPage } from './CourseDetailPage';
import { CoursesPage } from './CoursesPage';
import { DashboardPage } from './DashboardPage';
import { LoginPage } from './LoginPage';

const course = (id: number, code: string, extra: Partial<Course> = {}): Course => ({
  id, code, title: `${code} title`, description: null, credits: 4, department: 'Computer Science', term: 'Autumn 2026',
  capacity: 30, enrolledCount: 10, seatsLeft: 20, instructor: 'Dr. Iyer', schedule: 'Mon 10:00', enrollmentOpen: true, ...extra,
});

const emptyRecord: AcademicRecord = { cgpa: null, completedCredits: 0, terms: [], enrollments: [] };

describe('LoginPage demo mode', () => {
  it('offers "Try as Student" when the backend has demo mode on, and signs in without a password', async () => {
    const fetchMock = mockFetch((url) => {
      if (url.pathname === '/auth/demo') return jsonResponse({ enabled: true });
      if (url.pathname === '/auth/demo-login') {
        return jsonResponse({ accessToken: fakeJwt({ sub: 'demo.student@studenthub.dev', role: 'ROLE_STUDENT' }),
          refreshToken: 'r', tokenType: 'Bearer', role: 'ROLE_STUDENT', email: 'demo.student@studenthub.dev', isFirstTimeLogin: false });
      }
      return jsonResponse({});
    });
    renderAt('/signin', <>
      <Route path="/signin" element={<LoginPage />} />
      <Route path="/dashboard" element={<p>DASHBOARD PAGE</p>} />
    </>);

    await userEvent.click(await screen.findByRole('button', { name: 'Try as Student' }));

    await waitFor(() => expect(screen.getByText('DASHBOARD PAGE')).toBeInTheDocument());
    const demoCall = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/auth/demo-login'))!;
    expect(JSON.parse(String(demoCall[1]!.body))).toEqual({ role: 'STUDENT' });
  });

  it('hides the demo buttons when demo mode is off', async () => {
    mockFetch(() => jsonResponse({ enabled: false }));
    renderAt('/signin', <Route path="/signin" element={<LoginPage />} />);

    await screen.findByRole('button', { name: /sign in/i });
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Try as Admin' })).not.toBeInTheDocument());
  });
});

describe('DashboardPage', () => {
  it('shows a student their CGPA, attendance warning and notices', async () => {
    signIn({ role: 'ROLE_STUDENT' });
    const dashboard: StudentDashboard = {
      name: 'Aarav Sharma', department: 'Computer Science', branch: 'CSE', cgpa: 8.25, completedCredits: 10,
      currentCredits: 15, overallAttendance: 81.5, coursesBelowAttendance: 1,
      currentCourses: [{ id: 1, course: { id: 1, code: 'CS202', title: 'DBMS', credits: 4, term: 'Autumn 2026', instructor: null, schedule: null },
        status: 'ENROLLED', grade: null, gradePoints: null, enrolledAt: null,
        attendance: { classesHeld: 14, classesAttended: 9, percentage: 64.3 } }],
      announcements: [{ id: 1, title: 'Mid-semester examinations', body: 'Exams soon', audienceDepartment: null, authorName: 'Office', createdAt: new Date().toISOString() }],
    };
    mockFetch(() => jsonResponse(dashboard));
    renderAt('/dashboard', <Route path="/dashboard" element={<DashboardPage />} />);

    expect(await screen.findByText('Welcome back, Aarav')).toBeInTheDocument();
    expect(screen.getByText('8.25')).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('below 75% in 1 course');
    expect(screen.getByText(/64.3%/)).toBeInTheDocument();
    expect(screen.getByText('Mid-semester examinations')).toBeInTheDocument();
  });

  it('shows an admin the totals and students with low attendance', async () => {
    signIn({ role: 'ROLE_ADMIN' });
    mockFetch(() => jsonResponse({
      totalStudents: 36, totalCourses: 12, activeEnrollments: 120, averageAttendance: 85.2,
      lowAttendance: [{ studentId: 7, studentName: 'Rohan Das', courseId: 3, courseCode: 'CS301', percentage: 58.3 }],
      courseFill: [{ courseId: 3, code: 'CS301', title: 'Machine Learning', enrolled: 17, capacity: 18 }],
      studentsByDepartment: [{ department: 'Computer Science', students: 18 }],
      recentAnnouncements: [],
    }));
    renderAt('/dashboard', <Route path="/dashboard" element={<DashboardPage />} />);

    expect(await screen.findByText('36')).toBeInTheDocument();
    expect(screen.getByText('85.2%')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Rohan Das' })).toHaveAttribute('href', '/students/7');
    expect(screen.getByText('17/18')).toBeInTheDocument();
  });
});

describe('Course catalog (student)', () => {
  it('enrolls in a course and refreshes seats and status', async () => {
    signIn({ role: 'ROLE_STUDENT' });
    let enrolled = false;
    const fetchMock = mockFetch((url, init) => {
      if (url.pathname === '/courses/2/enroll' && init.method === 'POST') { enrolled = true; return jsonResponse({}, 201); }
      if (url.pathname === '/courses') return jsonResponse([course(2, 'CS203', enrolled ? { enrolledCount: 11, seatsLeft: 19 } : {})]);
      if (url.pathname === '/students/me/academics') {
        return jsonResponse(enrolled ? { ...emptyRecord, enrollments: [{ id: 9, course: { ...course(2, 'CS203') }, status: 'ENROLLED',
          grade: null, gradePoints: null, enrolledAt: null, attendance: { classesHeld: 0, classesAttended: 0, percentage: null } }] } : emptyRecord);
      }
      return jsonResponse({});
    });
    renderAt('/courses', <Route path="/courses" element={<CoursesPage />} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Enroll in CS203' }));

    expect(await screen.findByText('Enrolled in CS203.')).toBeInTheDocument();
    expect(screen.getByText('✓ Enrolled')).toBeInTheDocument();
    expect(screen.getByText('19 seats left')).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([u, i]) => String(u).endsWith('/courses/2/enroll') && i?.method === 'POST')).toBe(true);
  });

  it('shows the server reason when enrollment is refused', async () => {
    signIn({ role: 'ROLE_STUDENT' });
    mockFetch((url, init) => {
      if (init.method === 'POST') return jsonResponse({ error: 'Enrolling would take you to 28 credits this term; the limit is 24' }, 400);
      if (url.pathname === '/courses') return jsonResponse([course(2, 'CS203')]);
      return jsonResponse(emptyRecord);
    });
    renderAt('/courses', <Route path="/courses" element={<CoursesPage />} />);

    await userEvent.click(await screen.findByRole('button', { name: 'Enroll in CS203' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('limit is 24');
  });

  it('disables enrolling in a full course and hides it for closed ones', async () => {
    signIn({ role: 'ROLE_STUDENT' });
    mockFetch((url) => (url.pathname === '/courses'
      ? jsonResponse([course(1, 'FULL1', { seatsLeft: 0, enrolledCount: 30 }), course(2, 'SHUT1', { enrollmentOpen: false })])
      : jsonResponse(emptyRecord)));
    renderAt('/courses', <Route path="/courses" element={<CoursesPage />} />);

    expect(await screen.findByRole('button', { name: 'Enroll in FULL1' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Enroll in SHUT1' })).not.toBeInTheDocument();
    expect(screen.getByText('Enrollment closed')).toBeInTheDocument();
  });
});

describe('Course detail (admin)', () => {
  const roster: RosterEntry[] = [
    { enrollmentId: 11, studentId: 1, studentName: 'Asha Rao', studentEmail: 'asha@x.com', branch: 'CSE', status: 'ENROLLED',
      grade: null, attendance: { classesHeld: 10, classesAttended: 9, percentage: 90 } },
  ];

  it('assigns a grade from the roster', async () => {
    signIn({ role: 'ROLE_ADMIN' });
    const fetchMock = mockFetch((url, init) => {
      if (url.pathname === '/enrollments/11/grade') return jsonResponse({ ...roster[0], grade: 'A+', status: 'COMPLETED' });
      if (url.pathname === '/courses/5/roster') return jsonResponse(roster);
      if (url.pathname === '/courses/5') return jsonResponse(course(5, 'CS201'));
      return jsonResponse({}, init.method ? 200 : 200);
    });
    renderAt('/courses/5', <Route path="/courses/:id" element={<CourseDetailPage />} />);

    await userEvent.selectOptions(await screen.findByLabelText('Grade for Asha Rao'), 'A+');

    await waitFor(() => {
      const call = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/enrollments/11/grade'));
      expect(JSON.parse(String(call![1]!.body))).toEqual({ grade: 'A+' });
    });
  });

  it('takes attendance: defaults to present, toggles a student, and saves', async () => {
    signIn({ role: 'ROLE_ADMIN' });
    const sheet = { date: '2026-10-20', recordedDates: ['2026-10-19'], rows: [
      { enrollmentId: 11, studentId: 1, studentName: 'Asha Rao', branch: 'CSE', present: null },
      { enrollmentId: 12, studentId: 2, studentName: 'Bala Das', branch: 'CSE', present: null },
    ] };
    const fetchMock = mockFetch((url, init) => {
      if (url.pathname === '/courses/5/attendance' && init.method === 'POST') {
        return jsonResponse({ ...sheet, rows: sheet.rows.map((r) => ({ ...r, present: r.enrollmentId === 11 })) });
      }
      if (url.pathname === '/courses/5/attendance') return jsonResponse(sheet);
      if (url.pathname === '/courses/5/roster') return jsonResponse(roster);
      return jsonResponse(course(5, 'CS201'));
    });
    renderAt('/courses/5', <Route path="/courses/:id" element={<CourseDetailPage />} />);

    await userEvent.click(await screen.findByRole('tab', { name: 'Take attendance' }));
    const balaToggle = await screen.findByRole('switch', { name: 'Bala Das present' });
    expect(balaToggle).toHaveAttribute('aria-checked', 'true');
    await userEvent.click(balaToggle);
    expect(balaToggle).toHaveTextContent('Absent');
    await userEvent.click(screen.getByRole('button', { name: 'Save attendance' }));

    expect(await screen.findByText(/Attendance saved/)).toBeInTheDocument();
    const post = fetchMock.mock.calls.find(([u, i]) => String(u).includes('/attendance') && i?.method === 'POST')!;
    expect(JSON.parse(String(post[1]!.body)).records).toEqual([
      { enrollmentId: 11, present: true }, { enrollmentId: 12, present: false },
    ]);
  });
});

describe('AnnouncementsPage', () => {
  it('lets an admin post to one department', async () => {
    signIn({ role: 'ROLE_ADMIN' });
    const fetchMock = mockFetch((_url, init) => (init.method === 'POST'
      ? jsonResponse({ id: 9, title: 'Lab closed', body: 'Maintenance', audienceDepartment: 'Electronics', authorName: 'Admin', createdAt: new Date().toISOString() }, 201)
      : jsonResponse([])));
    renderAt('/announcements', <Route path="/announcements" element={<AnnouncementsPage />} />);

    await screen.findByText('No announcements yet');
    await userEvent.type(screen.getByLabelText(/title/i), 'Lab closed');
    await userEvent.type(screen.getByLabelText(/message/i), 'Maintenance');
    await userEvent.type(screen.getByLabelText(/department/i), 'Electronics');
    await userEvent.click(screen.getByRole('button', { name: 'Post announcement' }));

    const item = await screen.findByText('Lab closed');
    expect(within(item.closest('li')!).getByText('Electronics')).toBeInTheDocument();
    const post = fetchMock.mock.calls.find(([, i]) => i?.method === 'POST')!;
    expect(JSON.parse(String(post[1]!.body))).toEqual({ title: 'Lab closed', body: 'Maintenance', audienceDepartment: 'Electronics' });
  });

  it("doesn't show the posting form to students", async () => {
    signIn({ role: 'ROLE_STUDENT' });
    mockFetch(() => jsonResponse([]));
    renderAt('/announcements', <Route path="/announcements" element={<AnnouncementsPage />} />);

    await screen.findByText('No announcements yet');
    expect(screen.queryByRole('button', { name: 'Post announcement' })).not.toBeInTheDocument();
  });
});
