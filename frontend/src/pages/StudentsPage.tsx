import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../api/client';
import { studentsApi } from '../api/students';
import type { Page, SortDirection, SortField, Student } from '../api/types';
import { Alert, Modal, Spinner } from '../components/ui';
import { useDebounce } from '../hooks/useDebounce';
import { formatDate } from '../lib/format';
import { StudentFormModal } from './StudentFormModal';

const COLUMNS: { field: SortField; label: string; className?: string }[] = [
  { field: 'name', label: 'Name' },
  { field: 'department', label: 'Department', className: 'hidden md:table-cell' },
  { field: 'branch', label: 'Branch', className: 'hidden md:table-cell' },
  { field: 'dateOfBirth', label: 'Date of birth', className: 'hidden lg:table-cell' },
  { field: 'createdAt', label: 'Registered', className: 'hidden lg:table-cell' },
];

function TemporaryPasswordModal({ student, onClose }: { student: Student | null; onClose: () => void }) {
  const [copied, setCopied] = useState(false);
  useEffect(() => setCopied(false), [student]);
  const copy = async () => {
    if (!student?.temporaryPassword) return;
    await navigator.clipboard.writeText(student.temporaryPassword);
    setCopied(true);
  };
  return (
    <Modal open={student !== null} onClose={onClose} title="Student registered"
           footer={<button type="button" className="btn-primary" onClick={onClose}>Done</button>}>
      <div className="space-y-4 text-sm">
        <p><span className="font-medium">{student?.name}</span> can now sign in with:</p>
        <dl className="space-y-3 rounded-lg bg-slate-50 p-4">
          <div><dt className="text-xs text-slate-500">Email</dt><dd className="font-medium">{student?.email}</dd></div>
          <div>
            <dt className="text-xs text-slate-500">Temporary password</dt>
            <dd className="mt-1 flex items-center gap-2">
              <code className="rounded bg-white px-2 py-1 font-mono text-base tracking-wider ring-1 ring-slate-200"
                    data-testid="temp-password">{student?.temporaryPassword}</code>
              <button type="button" className="btn-secondary px-3 py-1" onClick={copy}>{copied ? 'Copied ✓' : 'Copy'}</button>
            </dd>
          </div>
        </dl>
        <Alert kind="info">
          Share this securely. <strong>It won’t be shown again</strong>, expires in 48 hours, and the student must
          change it on first sign-in.
        </Alert>
      </div>
    </Modal>
  );
}

export function StudentsPage() {
  const [search, setSearch] = useState('');
  const [department, setDepartment] = useState('');
  const [branch, setBranch] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [sortBy, setSortBy] = useState<SortField>('createdAt');
  const [direction, setDirection] = useState<SortDirection>('desc');

  const [data, setData] = useState<Page<Student> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Student | null>(null);
  const [created, setCreated] = useState<Student | null>(null);
  const [deleting, setDeleting] = useState<Student | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);

  const debouncedSearch = useDebounce(search.trim());
  const debouncedDepartment = useDebounce(department.trim());
  const debouncedBranch = useDebounce(branch.trim());

  // Any filter change goes back to the first page
  useEffect(() => setPage(0), [debouncedSearch, debouncedDepartment, debouncedBranch, size]);

  // Only the latest request may update state, so a slow, older response can't overwrite newer results
  const requestSeq = useRef(0);

  const load = useCallback(async () => {
    const seq = ++requestSeq.current;
    setLoading(true);
    setError(null);
    try {
      const result = await studentsApi.list({
        search: debouncedSearch, department: debouncedDepartment, branch: debouncedBranch,
        page, size, sortBy, direction,
      });
      if (seq === requestSeq.current) setData(result);
    } catch (err) {
      if (seq === requestSeq.current) setError(err instanceof ApiError ? err.message : 'Could not load students.');
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, [debouncedSearch, debouncedDepartment, debouncedBranch, page, size, sortBy, direction]);

  useEffect(() => { load(); }, [load]);

  const toggleSort = (field: SortField) => {
    if (sortBy === field) setDirection((d) => (d === 'asc' ? 'desc' : 'asc'));
    else { setSortBy(field); setDirection('asc'); }
  };

  const openCreate = () => { setEditing(null); setFormOpen(true); };
  const openEdit = (student: Student) => { setEditing(student); setFormOpen(true); };

  const handleSaved = (saved: Student, wasCreated: boolean) => {
    setFormOpen(false);
    setNotice(wasCreated ? null : `Saved changes to ${saved.name}.`);
    if (wasCreated) setCreated(saved);
    load();
  };

  const confirmDelete = async () => {
    if (!deleting) return;
    setDeleteBusy(true);
    try {
      await studentsApi.remove(deleting.id);
      setNotice(`Deleted ${deleting.name}.`);
      setDeleting(null);
      // Step back a page if we just removed the last row on it
      if (data && data.content.length === 1 && page > 0) setPage(page - 1);
      else load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not delete the student.');
      setDeleting(null);
    } finally {
      setDeleteBusy(false);
    }
  };

  const hasFilters = Boolean(search || department || branch);
  const from = data && data.totalElements > 0 ? data.page * data.size + 1 : 0;
  const to = data ? data.page * data.size + data.content.length : 0;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-semibold text-slate-900">Students</h1>
          <p className="mt-1 text-sm text-slate-500">
            {data ? `${data.totalElements} ${data.totalElements === 1 ? 'student' : 'students'}` : 'Loading…'}
          </p>
        </div>
        <button type="button" className="btn-primary" onClick={openCreate}>+ Register student</button>
      </div>

      {notice && <Alert kind="success">{notice}</Alert>}
      {error && <Alert>{error}</Alert>}

      <div className="card p-4">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-4">
          <input className="input sm:col-span-2" type="search" placeholder="Search by name or email…"
                 aria-label="Search students" value={search} onChange={(e) => setSearch(e.target.value)} />
          <input className="input" placeholder="Department" aria-label="Filter by department"
                 value={department} onChange={(e) => setDepartment(e.target.value)} />
          <input className="input" placeholder="Branch" aria-label="Filter by branch"
                 value={branch} onChange={(e) => setBranch(e.target.value)} />
        </div>
      </div>

      <div className="card overflow-hidden">
        <div className="overflow-x-auto">
          <table className="min-w-full divide-y divide-slate-200 text-sm">
            <thead className="bg-slate-50">
              <tr>
                {COLUMNS.map((col) => (
                  <th key={col.field} scope="col" className={`px-4 py-3 text-left font-medium text-slate-600 ${col.className ?? ''}`}
                      aria-sort={sortBy === col.field ? (direction === 'asc' ? 'ascending' : 'descending') : 'none'}>
                    <button type="button" className="inline-flex items-center gap-1 hover:text-slate-900"
                            onClick={() => toggleSort(col.field)}>
                      {col.label}
                      <span className="text-xs text-slate-400">
                        {sortBy === col.field ? (direction === 'asc' ? '▲' : '▼') : '↕'}
                      </span>
                    </button>
                  </th>
                ))}
                <th scope="col" className="px-4 py-3 text-right font-medium text-slate-600">Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 bg-white">
              {data?.content.map((s) => (
                <tr key={s.id} className="hover:bg-slate-50">
                  <td className="px-4 py-3">
                    <Link to={`/students/${s.id}`} className="font-medium text-slate-900 hover:text-indigo-600">{s.name}</Link>
                    <p className="text-xs text-slate-500">{s.email}</p>
                  </td>
                  <td className="hidden px-4 py-3 text-slate-700 md:table-cell">{s.department}</td>
                  <td className="hidden px-4 py-3 md:table-cell">
                    <span className="rounded-full bg-indigo-50 px-2 py-0.5 text-xs font-medium text-indigo-700">{s.branch}</span>
                  </td>
                  <td className="hidden px-4 py-3 text-slate-700 lg:table-cell">{formatDate(s.dateOfBirth)}</td>
                  <td className="hidden px-4 py-3 text-slate-500 lg:table-cell">{formatDate(s.createdAt)}</td>
                  <td className="whitespace-nowrap px-4 py-3 text-right">
                    <button type="button" className="btn-ghost" onClick={() => openEdit(s)}
                            aria-label={`Edit ${s.name}`}>Edit</button>
                    <button type="button" className="btn-ghost text-rose-600 hover:bg-rose-50 hover:text-rose-700"
                            onClick={() => setDeleting(s)} aria-label={`Delete ${s.name}`}>Delete</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {loading && !data && (
          <div className="flex justify-center py-16 text-slate-400"><Spinner className="h-6 w-6" /></div>
        )}
        {data && data.content.length === 0 && (
          <div className="px-4 py-16 text-center">
            <p className="font-medium text-slate-900">{hasFilters ? 'No students match your filters' : 'No students yet'}</p>
            <p className="mt-1 text-sm text-slate-500">
              {hasFilters ? 'Try a different search.' : 'Register the first student to get started.'}
            </p>
          </div>
        )}

        {data && data.totalElements > 0 && (
          <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 px-4 py-3 text-sm">
            <div className="flex items-center gap-2 text-slate-500">
              Showing {from}–{to} of {data.totalElements}
              {loading && <Spinner />}
            </div>
            <div className="flex items-center gap-3">
              <label className="flex items-center gap-2 text-slate-500">
                Rows
                <select className="input w-auto py-1" value={size} onChange={(e) => setSize(Number(e.target.value))}>
                  {[10, 25, 50].map((n) => <option key={n} value={n}>{n}</option>)}
                </select>
              </label>
              <button type="button" className="btn-secondary px-3 py-1" disabled={page === 0 || loading}
                      onClick={() => setPage((p) => p - 1)}>Previous</button>
              <span className="text-slate-500">Page {data.page + 1} of {Math.max(data.totalPages, 1)}</span>
              <button type="button" className="btn-secondary px-3 py-1" disabled={data.last || loading}
                      onClick={() => setPage((p) => p + 1)}>Next</button>
            </div>
          </div>
        )}
      </div>

      <StudentFormModal open={formOpen} student={editing} onClose={() => setFormOpen(false)} onSaved={handleSaved} />
      <TemporaryPasswordModal student={created} onClose={() => setCreated(null)} />

      <Modal open={deleting !== null} onClose={() => !deleteBusy && setDeleting(null)} title="Delete student?"
        footer={
          <>
            <button type="button" className="btn-secondary" onClick={() => setDeleting(null)} disabled={deleteBusy}>Cancel</button>
            <button type="button" className="btn-danger" onClick={confirmDelete} disabled={deleteBusy}>
              {deleteBusy && <Spinner />} Delete
            </button>
          </>
        }>
        <p className="text-sm text-slate-600">
          This permanently deletes <span className="font-medium text-slate-900">{deleting?.name}</span> and their login
          account. This can’t be undone.
        </p>
      </Modal>
    </div>
  );
}
