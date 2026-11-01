import { Link, useParams } from 'react-router-dom';
import { academicsApi } from '../api/academics';
import { studentsApi } from '../api/students';
import { AcademicRecordView } from '../components/AcademicRecordView';
import { LoadingBlock } from '../components/academic';
import { Alert } from '../components/ui';
import { useApi } from '../hooks/useApi';
import { formatDate } from '../lib/format';

/** Admin view of one student's profile and academic record. */
export function StudentDetailPage() {
  const id = Number(useParams().id);
  const student = useApi(() => studentsApi.get(id), [id]);
  const record = useApi(() => academicsApi.recordOf(id), [id]);

  const error = student.error ?? record.error;
  if (error) return <Alert>{error}</Alert>;
  if (!student.data || !record.data) return <LoadingBlock />;
  const s = student.data;

  return (
    <div className="space-y-6">
      <div>
        <Link to="/students" className="text-sm text-slate-500 hover:text-slate-900">← All students</Link>
        <div className="mt-2 flex flex-wrap items-center gap-4">
          <div className="flex h-14 w-14 items-center justify-center rounded-full bg-indigo-100 text-lg font-semibold text-indigo-700">
            {s.name.split(/\s+/).map((p) => p[0]).slice(0, 2).join('').toUpperCase()}
          </div>
          <div>
            <h1 className="text-2xl font-semibold text-slate-900">{s.name}</h1>
            <p className="text-sm text-slate-500">
              {s.email} · {s.department} · {s.branch} · born {formatDate(s.dateOfBirth)}
            </p>
          </div>
        </div>
      </div>
      <AcademicRecordView record={record.data} />
    </div>
  );
}
