import { useState, type FormEvent } from 'react';
import { announcementsApi } from '../api/academics';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { EmptyState, LoadingBlock, PageHeader, timeAgo } from '../components/academic';
import { Alert, Field, Spinner } from '../components/ui';
import { useApi } from '../hooks/useApi';

export function AnnouncementsPage() {
  const { isAdmin } = useAuth();
  const feed = useApi(announcementsApi.list);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [department, setDepartment] = useState('');
  const [posting, setPosting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const post = async (e: FormEvent) => {
    e.preventDefault();
    if (!title.trim() || !body.trim()) {
      setError('Add a title and a message.');
      return;
    }
    setPosting(true);
    setError(null);
    try {
      const created = await announcementsApi.create({ title: title.trim(), body: body.trim(), audienceDepartment: department.trim() || undefined });
      feed.setData([created, ...(feed.data ?? [])]);
      setTitle(''); setBody(''); setDepartment('');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not post the announcement.');
    } finally {
      setPosting(false);
    }
  };

  const remove = async (id: number) => {
    try {
      await announcementsApi.remove(id);
      feed.setData((feed.data ?? []).filter((a) => a.id !== id));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not delete the announcement.');
    }
  };

  return (
    <div className="space-y-6">
      <PageHeader title="Announcements"
                  subtitle={isAdmin ? 'Post notices to everyone or to one department' : 'Notices for everyone and for your department'} />
      {error && <Alert>{error}</Alert>}

      <div className={`grid grid-cols-1 gap-6 ${isAdmin ? 'lg:grid-cols-3' : ''}`}>
        {isAdmin && (
          <form onSubmit={post} className="card h-fit space-y-4 p-5" noValidate>
            <h2 className="text-sm font-semibold text-slate-900">New announcement</h2>
            <Field label="Title" required maxLength={150} value={title} onChange={(e) => setTitle(e.target.value)} />
            <div>
              <label htmlFor="announcement-body" className="mb-1 block text-sm font-medium text-slate-700">
                Message<span className="text-rose-500"> *</span>
              </label>
              <textarea id="announcement-body" className="input min-h-[120px]" maxLength={2000} value={body}
                        onChange={(e) => setBody(e.target.value)} />
            </div>
            <Field label="Department (optional)" value={department} onChange={(e) => setDepartment(e.target.value)}
                   hint="Leave blank to notify everyone" placeholder="e.g. Computer Science" />
            <button type="submit" className="btn-primary w-full" disabled={posting}>{posting && <Spinner />} Post announcement</button>
          </form>
        )}

        <div className={isAdmin ? 'lg:col-span-2' : ''}>
          {!feed.data ? <LoadingBlock /> : feed.data.length === 0 ? (
            <div className="card"><EmptyState title="No announcements yet" /></div>
          ) : (
            <ul className="space-y-4">
              {feed.data.map((a) => (
                <li key={a.id} className="card p-5">
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <h3 className="font-semibold text-slate-900">{a.title}</h3>
                      <p className="mt-0.5 text-xs text-slate-500">
                        {a.authorName ?? 'Administration'} · {timeAgo(a.createdAt)}
                        {' · '}
                        <span className={a.audienceDepartment ? 'text-violet-700' : ''}>{a.audienceDepartment ?? 'Everyone'}</span>
                      </p>
                    </div>
                    {isAdmin && (
                      <button type="button" className="btn-ghost text-rose-600 hover:bg-rose-50" aria-label={`Delete ${a.title}`}
                              onClick={() => remove(a.id)}>Delete</button>
                    )}
                  </div>
                  <p className="mt-3 whitespace-pre-line text-sm text-slate-700">{a.body}</p>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
