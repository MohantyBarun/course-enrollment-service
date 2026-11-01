import { Link } from 'react-router-dom';
import { academicsApi } from '../api/academics';
import { AcademicRecordView } from '../components/AcademicRecordView';
import { LoadingBlock, PageHeader } from '../components/academic';
import { Alert } from '../components/ui';
import { useApi } from '../hooks/useApi';

export function MyCoursesPage() {
  const { data, error } = useApi(academicsApi.myRecord);
  return (
    <div className="space-y-6">
      <PageHeader title="My courses" subtitle="Grades, attendance and grade point averages by term"
                  action={<Link to="/courses" className="btn-secondary">Browse catalog</Link>} />
      {error && <Alert>{error}</Alert>}
      {!data ? !error && <LoadingBlock /> : <AcademicRecordView record={data} />}
    </div>
  );
}
