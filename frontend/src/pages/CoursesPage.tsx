import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { academicsApi, coursesApi } from '../api/academics';
import { ApiError } from '../api/client';
import type { Course, MyEnrollment } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { EmptyState, GradeBadge, LoadingBlock, PageHeader, ProgressBar } from '../components/academic';
import { Alert, Modal, Spinner } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { CourseFormModal } from './CourseFormModal';

const MAX_CREDITS = 24;

export function CoursesPage() {
  const { isAdmin } = useAuth();
  return isAdmin ? <AdminCourses /> : <Catalog />;
}

/** Sorts terms like "Autumn 2026" newest first. */
function termKey(term: string): string {
  const [season, year] = term.split(' ');
  const order: Record<string, number> = { spring: 1, winter: 1, summer: 2, autumn: 3, fall: 3, monsoon: 3 };
  return `${year ?? ''}${order[season?.toLowerCase()] ?? 0}`;
}

function useCourseFilters(courses: Course[] | null) {
  const [search, setSearch] = useState('');
  const [term, setTerm] = useState<string | null>(null);
  const [department, setDepartment] = useState('');

  const terms = useMemo(() => [...new Set((courses ?? []).map((c) => c.term))].sort((a, b) => termKey(b).localeCompare(termKey(a))), [courses]);
  const departments = useMemo(() => [...new Set((courses ?? []).map((c) => c.department))].sort(), [courses]);
  const activeTerm = term ?? terms[0] ?? '';

  const filtered = useMemo(() => (courses ?? []).filter((c) => {
    const q = search.trim().toLowerCase();
    return (!activeTerm || c.term === activeTerm)
      && (!department || c.department === department)
      && (!q || [c.code, c.title, c.instructor ?? ''].some((v) => v.toLowerCase().includes(q)));
  }), [courses, search, activeTerm, department]);

  const controls = (
    <div className="card grid grid-cols-1 gap-3 p-4 sm:grid-cols-4">
      <input className="input sm:col-span-2" type="search" placeholder="Search code, title or instructor…"
             aria-label="Search courses" value={search} onChange={(e) => setSearch(e.target.value)} />
      <select className="input" aria-label="Term" value={activeTerm} onChange={(e) => setTerm(e.target.value)}>
        {terms.map((t) => <option key={t} value={t}>{t}</option>)}
      </select>
      <select className="input" aria-label="Department" value={department} onChange={(e) => setDepartment(e.target.value)}>
        <option value="">All departments</option>
        {departments.map((d) => <option key={d} value={d}>{d}</option>)}
      </select>
    </div>
  );
  return { filtered, controls, activeTerm };
}

// ------------------------------------------------------------------ admin

function AdminCourses() {
  const { data: courses, error, reload } = useApi(() => coursesApi.list());
  const { filtered, controls, activeTerm } = useCourseFilters(courses);
  const [editing, setEditing] = useState<Course | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [deleting, setDeleting] = useState<Course | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const confirmDelete = async () => {
    if (!deleting) return;
    setBusy(true);
    try {
      await coursesApi.remove(deleting.id);
      setNotice(`Deleted ${deleting.code}.`);
      reload();
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : 'Could not delete the course.');
    } finally {
      setBusy(false);
      setDeleting(null);
    }
  };

  return (
    <div className="space-y-6">
      <PageHeader title="Courses" subtitle="Manage the catalog, rosters, grades and attendance"
                  action={<button type="button" className="btn-primary" onClick={() => { setEditing(null); setFormOpen(true); }}>+ New course</button>} />
      {notice && <Alert kind="success">{notice}</Alert>}
      {(error || actionError) && <Alert>{error ?? actionError}</Alert>}
      {controls}

      {!courses ? <LoadingBlock /> : (
        <div className="card overflow-hidden">
          <div className="overflow-x-auto">
            <table className="min-w-full divide-y divide-slate-200 text-sm">
              <thead className="bg-slate-50 text-left text-slate-600">
                <tr>
                  <th className="px-4 py-3 font-medium">Course</th>
                  <th className="hidden px-4 py-3 font-medium md:table-cell">Instructor</th>
                  <th className="px-4 py-3 font-medium">Enrollment</th>
                  <th className="hidden px-4 py-3 font-medium sm:table-cell">Status</th>
                  <th className="px-4 py-3 text-right font-medium">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 bg-white">
                {filtered.map((c) => (
                  <tr key={c.id} className="hover:bg-slate-50">
                    <td className="px-4 py-3">
                      <Link to={`/courses/${c.id}`} className="font-medium text-slate-900 hover:text-indigo-600">{c.code} · {c.title}</Link>
                      <p className="text-xs text-slate-500">{c.department} · {c.credits} credits</p>
                    </td>
                    <td className="hidden px-4 py-3 text-slate-600 md:table-cell">{c.instructor ?? '—'}</td>
                    <td className="w-48 px-4 py-3">
                      <div className="mb-1 text-xs text-slate-500">{c.enrolledCount}/{c.capacity}</div>
                      <ProgressBar value={c.enrolledCount} max={c.capacity} />
                    </td>
                    <td className="hidden px-4 py-3 sm:table-cell">
                      <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${c.enrollmentOpen ? 'bg-emerald-50 text-emerald-700' : 'bg-slate-100 text-slate-600'}`}>
                        {c.enrollmentOpen ? 'Open' : 'Closed'}
                      </span>
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-right">
                      <Link to={`/courses/${c.id}`} className="btn-ghost">Manage</Link>
                      <button type="button" className="btn-ghost" onClick={() => { setEditing(c); setFormOpen(true); }}>Edit</button>
                      <button type="button" className="btn-ghost text-rose-600 hover:bg-rose-50" aria-label={`Delete ${c.code}`}
                              onClick={() => { setActionError(null); setDeleting(c); }}>Delete</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!filtered.length && <EmptyState title="No courses match" />}
        </div>
      )}

      <CourseFormModal open={formOpen} course={editing} defaultTerm={activeTerm} onClose={() => setFormOpen(false)}
                       onSaved={(c) => { setFormOpen(false); setNotice(`Saved ${c.code}.`); reload(); }} />
      <Modal open={deleting !== null} onClose={() => !busy && setDeleting(null)} title="Delete course?"
             footer={<>
               <button type="button" className="btn-secondary" onClick={() => setDeleting(null)} disabled={busy}>Cancel</button>
               <button type="button" className="btn-danger" onClick={confirmDelete} disabled={busy}>{busy && <Spinner />} Delete</button>
             </>}>
        <p className="text-sm text-slate-600">
          Delete <span className="font-medium text-slate-900">{deleting?.code} · {deleting?.title}</span>? Courses with
          enrollments can’t be deleted; close enrollment instead.
        </p>
      </Modal>
    </div>
  );
}

// ------------------------------------------------------------------ student catalog

function Catalog() {
  const courses = useApi(() => coursesApi.list());
  const record = useApi(academicsApi.myRecord);
  const { filtered, controls, activeTerm } = useCourseFilters(courses.data);
  const [pending, setPending] = useState<number | null>(null);
  const [message, setMessage] = useState<{ kind: 'success' | 'error'; text: string } | null>(null);

  const mine = useMemo(() => {
    const map = new Map<number, MyEnrollment>();
    record.data?.enrollments.forEach((e) => map.set(e.course.id, e));
    return map;
  }, [record.data]);

  const creditsThisTerm = (record.data?.enrollments ?? [])
    .filter((e) => e.status === 'ENROLLED' && e.course.term === activeTerm)
    .reduce((sum, e) => sum + e.course.credits, 0);

  const act = async (course: Course, action: 'enroll' | 'drop') => {
    setPending(course.id);
    setMessage(null);
    try {
      if (action === 'enroll') await coursesApi.enroll(course.id);
      else await coursesApi.drop(course.id);
      setMessage({ kind: 'success', text: action === 'enroll' ? `Enrolled in ${course.code}.` : `Dropped ${course.code}.` });
      await Promise.all([courses.reload(), record.reload()]);
    } catch (err) {
      setMessage({ kind: 'error', text: err instanceof ApiError ? err.message : 'Something went wrong.' });
    } finally {
      setPending(null);
    }
  };

  const error = courses.error ?? record.error;

  return (
    <div className="space-y-6">
      <PageHeader title="Course catalog" subtitle="Browse courses and manage your enrollment"
        action={
          <div className="w-56">
            <div className="mb-1 flex justify-between text-xs text-slate-500">
              <span>Credits in {activeTerm || 'term'}</span><span className="font-medium text-slate-900">{creditsThisTerm}/{MAX_CREDITS}</span>
            </div>
            <ProgressBar value={creditsThisTerm} max={MAX_CREDITS} tone={creditsThisTerm > 20 ? 'warn' : 'brand'} />
          </div>
        } />
      {message && <Alert kind={message.kind}>{message.text}</Alert>}
      {error && <Alert>{error}</Alert>}
      {controls}

      {!courses.data || !record.data ? <LoadingBlock /> : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
          {filtered.map((c) => {
            const enrollment = mine.get(c.id);
            const status = enrollment?.status;
            return (
              <article key={c.id} className="card flex flex-col p-5">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <p className="text-xs font-semibold uppercase tracking-wide text-indigo-600">{c.code}</p>
                    <h3 className="mt-1 font-semibold text-slate-900">{c.title}</h3>
                  </div>
                  <span className="whitespace-nowrap rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-700">{c.credits} cr</span>
                </div>
                {c.description && <p className="mt-2 line-clamp-2 text-sm text-slate-600">{c.description}</p>}
                <dl className="mt-3 space-y-1 text-xs text-slate-500">
                  {c.instructor && <div>👩‍🏫 {c.instructor}</div>}
                  {c.schedule && <div>🗓 {c.schedule}</div>}
                  <div>🏛 {c.department}</div>
                </dl>
                <div className="mt-4">
                  <div className="mb-1 flex justify-between text-xs text-slate-500">
                    <span>{c.seatsLeft === 0 ? 'Full' : `${c.seatsLeft} seats left`}</span><span>{c.enrolledCount}/{c.capacity}</span>
                  </div>
                  <ProgressBar value={c.enrolledCount} max={c.capacity} tone={c.seatsLeft === 0 ? 'bad' : c.seatsLeft <= 3 ? 'warn' : 'brand'} />
                </div>
                <div className="mt-4 flex items-center justify-between gap-2 border-t border-slate-100 pt-4">
                  {status === 'COMPLETED' ? (
                    <span className="flex items-center gap-2 text-sm text-slate-600">Completed <GradeBadge grade={enrollment!.grade} /></span>
                  ) : status === 'ENROLLED' ? (
                    <>
                      <span className="text-sm font-medium text-emerald-700">✓ Enrolled</span>
                      {c.enrollmentOpen && (
                        <button type="button" className="btn-secondary px-3 py-1" disabled={pending === c.id}
                                onClick={() => act(c, 'drop')} aria-label={`Drop ${c.code}`}>
                          {pending === c.id && <Spinner />} Drop
                        </button>
                      )}
                    </>
                  ) : !c.enrollmentOpen ? (
                    <span className="text-sm text-slate-400">Enrollment closed</span>
                  ) : (
                    <>
                      <span className="text-sm text-slate-400">{c.seatsLeft === 0 ? 'No seats available' : ''}</span>
                      <button type="button" className="btn-primary px-3 py-1" disabled={pending === c.id || c.seatsLeft === 0}
                              onClick={() => act(c, 'enroll')} aria-label={`Enroll in ${c.code}`}>
                        {pending === c.id && <Spinner />} Enroll
                      </button>
                    </>
                  )}
                </div>
              </article>
            );
          })}
          {!filtered.length && <div className="card md:col-span-2 xl:col-span-3"><EmptyState title="No courses match" /></div>}
        </div>
      )}
    </div>
  );
}
