import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { academicsApi, coursesApi } from '../api/academics';
import { ApiError } from '../api/client';
import { GRADES, type AttendanceSheetRow, type GradeLabel, type RosterEntry } from '../api/types';
import { AttendanceBadge, EmptyState, GradeBadge, LoadingBlock, ProgressBar } from '../components/academic';
import { Alert, Spinner } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { formatDate } from '../lib/format';

const todayIso = () => new Date().toISOString().slice(0, 10);

export function CourseDetailPage() {
  const id = Number(useParams().id);
  const course = useApi(() => coursesApi.get(id), [id]);
  const [tab, setTab] = useState<'roster' | 'attendance'>('roster');

  if (course.error) return <Alert>{course.error}</Alert>;
  if (!course.data) return <LoadingBlock />;
  const c = course.data;

  return (
    <div className="space-y-6">
      <div>
        <Link to="/courses" className="text-sm text-slate-500 hover:text-slate-900">← All courses</Link>
        <div className="mt-2 flex flex-wrap items-end justify-between gap-4">
          <div>
            <p className="text-sm font-semibold text-indigo-600">{c.code} · {c.term}</p>
            <h1 className="text-2xl font-semibold text-slate-900">{c.title}</h1>
            <p className="mt-1 text-sm text-slate-500">
              {c.department} · {c.credits} credits{c.instructor && ` · ${c.instructor}`}{c.schedule && ` · ${c.schedule}`}
            </p>
          </div>
          <div className="w-56">
            <div className="mb-1 flex justify-between text-xs text-slate-500">
              <span>{c.enrollmentOpen ? 'Enrollment open' : 'Enrollment closed'}</span>
              <span className="font-medium text-slate-900">{c.enrolledCount}/{c.capacity}</span>
            </div>
            <ProgressBar value={c.enrolledCount} max={c.capacity} />
          </div>
        </div>
      </div>

      <div className="flex gap-1 border-b border-slate-200" role="tablist">
        {([['roster', 'Roster & grades'], ['attendance', 'Take attendance']] as const).map(([key, label]) => (
          <button key={key} type="button" role="tab" aria-selected={tab === key} onClick={() => setTab(key)}
                  className={`-mb-px border-b-2 px-4 py-2 text-sm font-medium ${tab === key
                    ? 'border-indigo-600 text-indigo-700' : 'border-transparent text-slate-500 hover:text-slate-900'}`}>
            {label}
          </button>
        ))}
      </div>

      {tab === 'roster' ? <Roster courseId={id} /> : <Attendance courseId={id} />}
    </div>
  );
}

function Roster({ courseId }: { courseId: number }) {
  const roster = useApi(() => coursesApi.roster(courseId), [courseId]);
  const [saving, setSaving] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const setGrade = async (row: RosterEntry, grade: GradeLabel) => {
    setSaving(row.enrollmentId);
    setError(null);
    try {
      const updated = await academicsApi.assignGrade(row.enrollmentId, grade);
      roster.setData((roster.data ?? []).map((r) => (r.enrollmentId === updated.enrollmentId ? updated : r)));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save the grade.');
    } finally {
      setSaving(null);
    }
  };

  if (roster.error) return <Alert>{roster.error}</Alert>;
  if (!roster.data) return <LoadingBlock />;
  const graded = roster.data.filter((r) => r.grade).length;

  return (
    <div className="space-y-3">
      {error && <Alert>{error}</Alert>}
      <p className="text-sm text-slate-500">
        {roster.data.length} students · {graded} graded. Choosing a grade marks the course as completed for that student.
      </p>
      <div className="card overflow-hidden">
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50 text-left text-slate-600">
              <tr>
                <th className="px-4 py-3 font-medium">Student</th>
                <th className="px-4 py-3 font-medium">Attendance</th>
                <th className="px-4 py-3 font-medium">Grade</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 bg-white">
              {roster.data.map((r) => (
                <tr key={r.enrollmentId}>
                  <td className="px-4 py-3">
                    <Link to={`/students/${r.studentId}`} className="font-medium text-slate-900 hover:text-indigo-600">{r.studentName}</Link>
                    <p className="text-xs text-slate-500">{r.studentEmail} · {r.branch}</p>
                  </td>
                  <td className="px-4 py-3"><AttendanceBadge attendance={r.attendance} /></td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <select className="input w-24 py-1" aria-label={`Grade for ${r.studentName}`} value={r.grade ?? ''}
                              disabled={saving === r.enrollmentId}
                              onChange={(e) => setGrade(r, e.target.value as GradeLabel)}>
                        <option value="" disabled>—</option>
                        {GRADES.map((g) => <option key={g.label} value={g.label}>{g.label} ({g.points})</option>)}
                      </select>
                      {saving === r.enrollmentId ? <Spinner /> : <GradeBadge grade={r.grade} />}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!roster.data.length && <EmptyState title="No students enrolled yet" />}
      </div>
    </div>
  );
}

function Attendance({ courseId }: { courseId: number }) {
  const [date, setDate] = useState(todayIso());
  const sheet = useApi(() => coursesApi.attendanceSheet(courseId, date), [courseId, date]);
  const [marks, setMarks] = useState<Record<number, boolean>>({});
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);

  // Start from what's recorded; untaken attendance defaults to present
  useEffect(() => {
    if (!sheet.data) return;
    setMarks(Object.fromEntries(sheet.data.rows.map((r: AttendanceSheetRow) => [r.enrollmentId, r.present ?? true])));
  }, [sheet.data]);

  const alreadyTaken = useMemo(() => sheet.data?.rows.some((r) => r.present !== null) ?? false, [sheet.data]);
  const presentCount = Object.values(marks).filter(Boolean).length;

  const save = async () => {
    if (!sheet.data) return;
    setSaving(true);
    setMessage(null);
    try {
      const updated = await coursesApi.recordAttendance(courseId, date,
        sheet.data.rows.map((r) => ({ enrollmentId: r.enrollmentId, present: marks[r.enrollmentId] ?? true })));
      sheet.setData(updated);
      setMessage({ kind: 'success', text: `Attendance saved for ${formatDate(date)}: ${presentCount}/${updated.rows.length} present.` });
    } catch (err) {
      setMessage({ kind: 'error', text: err instanceof ApiError ? err.message : 'Could not save attendance.' });
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-4">
      <div className="space-y-4 lg:col-span-3">
        <div className="card flex flex-wrap items-end justify-between gap-4 p-4">
          <div>
            <label htmlFor="class-date" className="mb-1 block text-sm font-medium text-slate-700">Class date</label>
            <input id="class-date" type="date" className="input w-auto" max={todayIso()} value={date}
                   onChange={(e) => { setMessage(null); setDate(e.target.value); }} />
          </div>
          <div className="flex items-center gap-2">
            <button type="button" className="btn-secondary px-3 py-1.5"
                    onClick={() => setMarks((m) => Object.fromEntries(Object.keys(m).map((k) => [k, true])))}>All present</button>
            <button type="button" className="btn-secondary px-3 py-1.5"
                    onClick={() => setMarks((m) => Object.fromEntries(Object.keys(m).map((k) => [k, false])))}>All absent</button>
          </div>
        </div>
        {message && <Alert kind={message.kind}>{message.text}</Alert>}
        {sheet.error && <Alert>{sheet.error}</Alert>}
        {!sheet.data ? <LoadingBlock /> : (
          <div className="card overflow-hidden">
            <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3 text-sm">
              <span className="text-slate-600">
                {alreadyTaken ? 'Attendance already recorded for this date: you can update it.' : 'Not yet recorded for this date.'}
              </span>
              <span className="font-medium text-slate-900">{presentCount}/{sheet.data.rows.length} present</span>
            </div>
            <ul className="divide-y divide-slate-100">
              {sheet.data.rows.map((r) => {
                const present = marks[r.enrollmentId] ?? true;
                return (
                  <li key={r.enrollmentId} className="flex items-center justify-between px-4 py-2.5">
                    <div>
                      <p className="text-sm font-medium text-slate-900">{r.studentName}</p>
                      <p className="text-xs text-slate-500">{r.branch}</p>
                    </div>
                    <button type="button" role="switch" aria-checked={present} aria-label={`${r.studentName} present`}
                            onClick={() => setMarks((m) => ({ ...m, [r.enrollmentId]: !present }))}
                            className={`w-24 rounded-full px-3 py-1 text-xs font-semibold transition ${present
                              ? 'bg-emerald-100 text-emerald-800 hover:bg-emerald-200' : 'bg-rose-100 text-rose-800 hover:bg-rose-200'}`}>
                      {present ? 'Present' : 'Absent'}
                    </button>
                  </li>
                );
              })}
            </ul>
            {!sheet.data.rows.length && <EmptyState title="No students currently enrolled" />}
            <div className="flex justify-end border-t border-slate-100 px-4 py-3">
              <button type="button" className="btn-primary" onClick={save} disabled={saving || !sheet.data.rows.length}>
                {saving && <Spinner />} {alreadyTaken ? 'Update attendance' : 'Save attendance'}
              </button>
            </div>
          </div>
        )}
      </div>

      <aside className="card h-fit p-4">
        <h3 className="text-sm font-semibold text-slate-900">Recorded classes</h3>
        <p className="mt-1 text-xs text-slate-500">{sheet.data?.recordedDates.length ?? 0} so far. Click one to review it.</p>
        <ul className="mt-3 max-h-80 space-y-1 overflow-y-auto">
          {sheet.data?.recordedDates.map((d) => (
            <li key={d}>
              <button type="button" onClick={() => { setMessage(null); setDate(d); }}
                      className={`w-full rounded-md px-2 py-1 text-left text-sm ${d === date ? 'bg-indigo-50 font-medium text-indigo-700' : 'text-slate-600 hover:bg-slate-50'}`}>
                {formatDate(d)}
              </button>
            </li>
          ))}
        </ul>
      </aside>
    </div>
  );
}
