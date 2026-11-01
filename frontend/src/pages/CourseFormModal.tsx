import { useEffect, useState, type FormEvent } from 'react';
import { coursesApi } from '../api/academics';
import { ApiError } from '../api/client';
import type { Course, CourseRequest } from '../api/types';
import { Alert, Field, Modal, Spinner } from '../components/ui';

interface Props {
  open: boolean;
  course: Course | null;
  defaultTerm?: string;
  onClose: () => void;
  onSaved: (course: Course) => void;
}

const empty = (term = ''): CourseRequest => ({
  code: '', title: '', description: '', credits: 3, department: '', term, capacity: 40, instructor: '', schedule: '',
  enrollmentOpen: true,
});

export function CourseFormModal({ open, course, defaultTerm, onClose, onSaved }: Props) {
  const [form, setForm] = useState<CourseRequest>(empty());
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const editing = course !== null;

  useEffect(() => {
    if (!open) return;
    setErrors({});
    setFormError(null);
    setForm(course ? {
      code: course.code, title: course.title, description: course.description ?? '', credits: course.credits,
      department: course.department, term: course.term, capacity: course.capacity,
      instructor: course.instructor ?? '', schedule: course.schedule ?? '', enrollmentOpen: course.enrollmentOpen,
    } : empty(defaultTerm));
  }, [open, course, defaultTerm]);

  const set = (key: keyof CourseRequest) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.type === 'number' ? Number(e.target.value) : e.target.value }));

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    const clientErrors: Record<string, string> = {};
    if (!/^[A-Za-z0-9-]{1,20}$/.test(form.code.trim())) clientErrors.code = 'Use up to 20 letters, numbers or dashes';
    if (!form.title.trim()) clientErrors.title = 'Title is required';
    if (!form.department.trim()) clientErrors.department = 'Department is required';
    if (!form.term.trim()) clientErrors.term = 'Term is required';
    if (form.credits < 1 || form.credits > 6) clientErrors.credits = 'Credits must be between 1 and 6';
    if (form.capacity < 1 || form.capacity > 500) clientErrors.capacity = 'Capacity must be between 1 and 500';
    setErrors(clientErrors);
    if (Object.keys(clientErrors).length) return;

    setSaving(true);
    setFormError(null);
    try {
      const saved = editing ? await coursesApi.update(course.id, form) : await coursesApi.create(form);
      onSaved(saved);
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 409) setErrors({ code: err.message });
        else { setErrors(err.fieldErrors); setFormError(err.message); }
      } else {
        setFormError('Something went wrong. Please try again.');
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal open={open} onClose={onClose} wide title={editing ? `Edit ${course.code}` : 'New course'}
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose}>Cancel</button>
          <button type="submit" form="course-form" className="btn-primary" disabled={saving}>
            {saving && <Spinner />} {editing ? 'Save changes' : 'Create course'}
          </button>
        </>
      }>
      <form id="course-form" onSubmit={handleSubmit} className="grid grid-cols-1 gap-4 sm:grid-cols-2" noValidate>
        {formError && <div className="sm:col-span-2"><Alert>{formError}</Alert></div>}
        <Field label="Course code" required value={form.code} onChange={set('code')} error={errors.code} placeholder="e.g. CS201" />
        <Field label="Title" required value={form.title} onChange={set('title')} error={errors.title} />
        <Field label="Department" required value={form.department} onChange={set('department')} error={errors.department} />
        <Field label="Term" required value={form.term} onChange={set('term')} error={errors.term} placeholder="e.g. Autumn 2026" />
        <Field label="Credits" type="number" min={1} max={6} required value={form.credits} onChange={set('credits')} error={errors.credits} />
        <Field label="Capacity" type="number" min={1} max={500} required value={form.capacity} onChange={set('capacity')} error={errors.capacity} />
        <Field label="Instructor" value={form.instructor} onChange={set('instructor')} error={errors.instructor} />
        <Field label="Schedule" value={form.schedule} onChange={set('schedule')} error={errors.schedule} placeholder="e.g. Mon/Wed 10:00–11:30" />
        <div className="sm:col-span-2">
          <label htmlFor="course-description" className="mb-1 block text-sm font-medium text-slate-700">Description</label>
          <textarea id="course-description" className="input min-h-[80px]" maxLength={1000} value={form.description}
                    onChange={set('description')} />
        </div>
        <label className="flex items-center gap-2 text-sm text-slate-700 sm:col-span-2">
          <input type="checkbox" className="h-4 w-4 rounded border-slate-300 text-indigo-600" checked={form.enrollmentOpen}
                 onChange={(e) => setForm((f) => ({ ...f, enrollmentOpen: e.target.checked }))} />
          Enrollment open (students can enroll and drop)
        </label>
      </form>
    </Modal>
  );
}
