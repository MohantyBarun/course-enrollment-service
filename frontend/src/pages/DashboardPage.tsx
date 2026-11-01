import { Link } from 'react-router-dom';
import { academicsApi } from '../api/academics';
import { useAuth } from '../auth/AuthContext';
import {
  ATTENDANCE_MINIMUM, AttendanceBadge, attendanceTone, EmptyState, formatGpa, LoadingBlock, PageHeader,
  ProgressBar, StatCard, timeAgo,
} from '../components/academic';
import { Alert } from '../components/ui';
import { useApi } from '../hooks/useApi';
import type { Announcement } from '../api/types';

export function DashboardPage() {
  const { isAdmin } = useAuth();
  return isAdmin ? <AdminDashboardView /> : <StudentDashboardView />;
}

function AnnouncementList({ items, empty }: { items: Announcement[]; empty: string }) {
  if (!items.length) return <EmptyState title={empty} />;
  return (
    <ul className="divide-y divide-slate-100">
      {items.map((a) => (
        <li key={a.id} className="px-5 py-4">
          <div className="flex items-start justify-between gap-3">
            <p className="font-medium text-slate-900">{a.title}</p>
            <span className="whitespace-nowrap text-xs text-slate-400">{timeAgo(a.createdAt)}</span>
          </div>
          <p className="mt-1 line-clamp-2 text-sm text-slate-600">{a.body}</p>
          {a.audienceDepartment && (
            <span className="mt-2 inline-block rounded-full bg-violet-50 px-2 py-0.5 text-xs font-medium text-violet-700">
              {a.audienceDepartment}
            </span>
          )}
        </li>
      ))}
    </ul>
  );
}

function Panel({ title, action, children }: { title: string; action?: React.ReactNode; children: React.ReactNode }) {
  return (
    <section className="card overflow-hidden">
      <div className="flex items-center justify-between border-b border-slate-100 px-5 py-3">
        <h2 className="text-sm font-semibold text-slate-900">{title}</h2>
        {action}
      </div>
      {children}
    </section>
  );
}

// ------------------------------------------------------------------ admin

function AdminDashboardView() {
  const { data, error } = useApi(academicsApi.adminDashboard);
  if (error) return <Alert>{error}</Alert>;
  if (!data) return <LoadingBlock />;

  const maxDept = Math.max(1, ...data.studentsByDepartment.map((d) => d.students));

  return (
    <div className="space-y-6">
      <PageHeader title="Dashboard" subtitle="Overview of students, courses and attendance this term" />

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard label="Students" value={data.totalStudents} />
        <StatCard label="Courses" value={data.totalCourses} />
        <StatCard label="Active enrollments" value={data.activeEnrollments} />
        <StatCard label="Average attendance" value={data.averageAttendance == null ? '—' : `${data.averageAttendance}%`}
                  tone={attendanceTone(data.averageAttendance)} hint={`Minimum required: ${ATTENDANCE_MINIMUM}%`} />
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <Panel title={`Students below ${ATTENDANCE_MINIMUM}% attendance`}
                 action={<span className="text-xs text-slate-500">{data.lowAttendance.length} flagged</span>}>
            {data.lowAttendance.length ? (
              <table className="min-w-full text-sm">
                <tbody className="divide-y divide-slate-100">
                  {data.lowAttendance.map((row) => (
                    <tr key={`${row.studentId}-${row.courseId}`}>
                      <td className="px-5 py-3">
                        <Link to={`/students/${row.studentId}`} className="font-medium text-slate-900 hover:text-indigo-600">
                          {row.studentName}
                        </Link>
                      </td>
                      <td className="px-5 py-3">
                        <Link to={`/courses/${row.courseId}`} className="text-slate-600 hover:text-indigo-600">{row.courseCode}</Link>
                      </td>
                      <td className="w-40 px-5 py-3">
                        <div className="flex items-center gap-2">
                          <ProgressBar value={row.percentage} tone="bad" />
                          <span className="w-12 text-right text-xs font-medium text-rose-600">{row.percentage}%</span>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <EmptyState title="Everyone is above the minimum 🎉" />
            )}
          </Panel>
        </div>

        <Panel title="Students by department">
          <ul className="space-y-4 px-5 py-4">
            {data.studentsByDepartment.map((d) => (
              <li key={d.department}>
                <div className="mb-1 flex justify-between text-sm">
                  <span className="text-slate-700">{d.department}</span>
                  <span className="font-medium text-slate-900">{d.students}</span>
                </div>
                <ProgressBar value={d.students} max={maxDept} />
              </li>
            ))}
            {!data.studentsByDepartment.length && <EmptyState title="No students yet" />}
          </ul>
        </Panel>
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
        <Panel title="Course enrollment (open courses)" action={<Link to="/courses" className="text-xs font-medium text-indigo-600">All courses →</Link>}>
          <ul className="space-y-4 px-5 py-4">
            {data.courseFill.map((c) => {
              const pct = Math.round((c.enrolled / c.capacity) * 100);
              return (
                <li key={c.courseId}>
                  <div className="mb-1 flex justify-between gap-3 text-sm">
                    <Link to={`/courses/${c.courseId}`} className="truncate text-slate-700 hover:text-indigo-600">
                      <span className="font-medium text-slate-900">{c.code}</span> · {c.title}
                    </Link>
                    <span className="whitespace-nowrap text-slate-500">{c.enrolled}/{c.capacity}</span>
                  </div>
                  <ProgressBar value={c.enrolled} max={c.capacity} tone={pct >= 90 ? 'bad' : pct >= 70 ? 'warn' : 'brand'} />
                </li>
              );
            })}
            {!data.courseFill.length && <EmptyState title="No open courses" />}
          </ul>
        </Panel>
        <Panel title="Recent announcements" action={<Link to="/announcements" className="text-xs font-medium text-indigo-600">Manage →</Link>}>
          <AnnouncementList items={data.recentAnnouncements} empty="No announcements yet" />
        </Panel>
      </div>
    </div>
  );
}

// ------------------------------------------------------------------ student

function StudentDashboardView() {
  const { data, error } = useApi(academicsApi.studentDashboard);
  if (error) return <Alert>{error}</Alert>;
  if (!data) return <LoadingBlock />;

  const firstName = data.name.split(' ')[0];

  return (
    <div className="space-y-6">
      <PageHeader title={`Welcome back, ${firstName}`} subtitle={`${data.department} · ${data.branch}`} />

      {data.coursesBelowAttendance > 0 && (
        <Alert>
          Your attendance is below {ATTENDANCE_MINIMUM}% in {data.coursesBelowAttendance}{' '}
          {data.coursesBelowAttendance === 1 ? 'course' : 'courses'}. You may not be allowed to sit the exams. Check{' '}
          <Link to="/my-courses" className="font-medium underline">My courses</Link>.
        </Alert>
      )}

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard label="CGPA" value={formatGpa(data.cgpa)} hint="10-point scale" tone={data.cgpa != null && data.cgpa >= 8 ? 'good' : 'default'} />
        <StatCard label="Credits earned" value={data.completedCredits} />
        <StatCard label="Credits this term" value={data.currentCredits} hint="Limit: 24" />
        <StatCard label="Attendance this term" value={data.overallAttendance == null ? '—' : `${data.overallAttendance}%`}
                  tone={attendanceTone(data.overallAttendance)} hint={`Minimum: ${ATTENDANCE_MINIMUM}%`} />
      </div>

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2">
          <Panel title="My courses this term" action={<Link to="/courses" className="text-xs font-medium text-indigo-600">Browse catalog →</Link>}>
            {data.currentCourses.length ? (
              <ul className="divide-y divide-slate-100">
                {data.currentCourses.map((e) => (
                  <li key={e.id} className="flex flex-wrap items-center justify-between gap-3 px-5 py-4">
                    <div>
                      <p className="font-medium text-slate-900">{e.course.code} · {e.course.title}</p>
                      <p className="text-xs text-slate-500">
                        {e.course.credits} credits{e.course.instructor && ` · ${e.course.instructor}`}{e.course.schedule && ` · ${e.course.schedule}`}
                      </p>
                    </div>
                    <AttendanceBadge attendance={e.attendance} />
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState title="You're not enrolled in any courses this term">
                <Link to="/courses" className="font-medium text-indigo-600">Browse the catalog</Link> to enroll.
              </EmptyState>
            )}
          </Panel>
        </div>
        <Panel title="Notices for you" action={<Link to="/announcements" className="text-xs font-medium text-indigo-600">All →</Link>}>
          <AnnouncementList items={data.announcements} empty="No announcements" />
        </Panel>
      </div>
    </div>
  );
}
