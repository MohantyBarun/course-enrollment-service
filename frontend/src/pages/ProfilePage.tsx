import { useEffect, useState } from 'react';
import { ApiError } from '../api/client';
import { studentsApi } from '../api/students';
import type { Student } from '../api/types';
import { Alert, Spinner } from '../components/ui';
import { formatDate } from '../lib/format';

function Detail({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div>
      <dt className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="mt-1 text-sm text-slate-900">{value || '—'}</dd>
    </div>
  );
}

export function ProfilePage() {
  const [student, setStudent] = useState<Student | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    studentsApi.me()
      .then(setStudent)
      .catch((err) => setError(err instanceof ApiError ? err.message : 'Could not load your profile.'));
  }, []);

  if (error) return <Alert>{error}</Alert>;
  if (!student) {
    return <div className="flex justify-center py-20 text-slate-400"><Spinner className="h-6 w-6" /></div>;
  }

  const initials = student.name.split(/\s+/).map((p) => p[0]).slice(0, 2).join('').toUpperCase();

  return (
    <div className="mx-auto max-w-3xl">
      <div className="card overflow-hidden">
        <div className="h-24 bg-gradient-to-r from-indigo-500 to-violet-500" />
        <div className="px-6 pb-6">
          <div className="-mt-10 flex items-end gap-4">
            <div className="flex h-20 w-20 items-center justify-center rounded-full border-4 border-white bg-indigo-100 text-2xl font-semibold text-indigo-700">
              {initials}
            </div>
            <div className="pb-1">
              <h1 className="text-xl font-semibold text-slate-900">{student.name}</h1>
              <p className="text-sm text-slate-500">{student.email}</p>
            </div>
          </div>
          <dl className="mt-8 grid grid-cols-1 gap-6 sm:grid-cols-2">
            <Detail label="Student ID" value={`#${student.id}`} />
            <Detail label="Date of birth" value={formatDate(student.dateOfBirth)} />
            <Detail label="Department" value={student.department} />
            <Detail label="Branch" value={student.branch} />
            <Detail label="Present address" value={student.presentAddress} />
            <Detail label="Permanent address" value={student.permanentAddress} />
            <Detail label="Enrolled on" value={formatDate(student.createdAt)} />
            <Detail label="Last updated" value={formatDate(student.updatedAt)} />
          </dl>
          <p className="mt-8 text-xs text-slate-500">Need to correct something? Contact your administrator.</p>
        </div>
      </div>
    </div>
  );
}
