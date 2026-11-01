import type { AcademicRecord } from '../api/types';
import { AttendanceBadge, EmptyState, formatGpa, GradeBadge, StatCard } from './academic';

/** CGPA, SGPA per term and every course with grade and attendance; shared by students and admins. */
export function AcademicRecordView({ record }: { record: AcademicRecord }) {
  const current = record.enrollments.filter((e) => e.status === 'ENROLLED');
  const byTerm = record.terms.map((t) => ({ ...t, courses: record.enrollments.filter((e) => e.course.term === t.term) }));

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <StatCard label="CGPA" value={formatGpa(record.cgpa)} hint="10-point scale" tone={record.cgpa != null && record.cgpa >= 8 ? 'good' : 'default'} />
        <StatCard label="Credits earned" value={record.completedCredits} />
        <StatCard label="Courses in progress" value={current.length} />
        <StatCard label="Terms" value={record.terms.length} />
      </div>

      {byTerm.length === 0 && <div className="card"><EmptyState title="No courses yet" /></div>}

      {byTerm.map((term) => (
        <section key={term.term} className="card overflow-hidden">
          <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 px-5 py-3">
            <h2 className="text-sm font-semibold text-slate-900">{term.term}</h2>
            <span className="text-xs text-slate-500">
              {term.credits} credits · SGPA <span className="font-semibold text-slate-900">{formatGpa(term.sgpa)}</span>
            </span>
          </div>
          <div className="overflow-x-auto">
            <table className="min-w-full text-sm">
              <thead className="text-left text-xs uppercase tracking-wide text-slate-500">
                <tr>
                  <th className="px-5 py-2 font-medium">Course</th>
                  <th className="px-5 py-2 font-medium">Credits</th>
                  <th className="px-5 py-2 font-medium">Attendance</th>
                  <th className="px-5 py-2 font-medium">Grade</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {term.courses.map((e) => (
                  <tr key={e.id}>
                    <td className="px-5 py-3">
                      <p className="font-medium text-slate-900">{e.course.code} · {e.course.title}</p>
                      <p className="text-xs text-slate-500">{e.course.instructor ?? ''}</p>
                    </td>
                    <td className="px-5 py-3 text-slate-700">{e.course.credits}</td>
                    <td className="px-5 py-3"><AttendanceBadge attendance={e.attendance} /></td>
                    <td className="px-5 py-3">
                      {e.status === 'ENROLLED'
                        ? <span className="rounded-full bg-sky-50 px-2 py-0.5 text-xs font-medium text-sky-700">In progress</span>
                        : <GradeBadge grade={e.grade} />}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ))}
    </div>
  );
}
