import { useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../api/client';
import { studentsApi } from '../api/students';
import type { Student, StudentRequest } from '../api/types';
import { Alert, Field, Modal, Spinner } from '../components/ui';

interface Props {
  open: boolean;
  /** Student to edit, or null to create a new one. */
  student: Student | null;
  onClose: () => void;
  onSaved: (saved: Student, created: boolean) => void;
}

const EMPTY: StudentRequest = {
  name: '', email: '', department: '', branch: '', dateOfBirth: '', presentAddress: '', permanentAddress: '',
};

const today = () => new Date().toISOString().slice(0, 10);

/** Client-side checks mirroring the backend's Bean Validation (the server re-validates). */
function validate(form: StudentRequest): Record<string, string> {
  const errors: Record<string, string> = {};
  if (form.name.trim().length < 2 || form.name.trim().length > 100) errors.name = 'Name must be 2–100 characters';
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) errors.email = 'Enter a valid email address';
  if (!form.department.trim()) errors.department = 'Department is required';
  if (!form.branch.trim()) errors.branch = 'Branch is required';
  if (!form.dateOfBirth) errors.dateOfBirth = 'Date of birth is required';
  else if (form.dateOfBirth >= today()) errors.dateOfBirth = 'Date of birth must be in the past';
  return errors;
}

export function StudentFormModal({ open, student, onClose, onSaved }: Props) {
  const [form, setForm] = useState<StudentRequest>(EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const editing = student !== null;

  useEffect(() => {
    if (!open) return;
    setErrors({});
    setFormError(null);
    setForm(student ? {
      name: student.name, email: student.email, department: student.department, branch: student.branch,
      dateOfBirth: student.dateOfBirth, presentAddress: student.presentAddress ?? '',
      permanentAddress: student.permanentAddress ?? '',
    } : EMPTY);
  }, [open, student]);

  const set = (key: keyof StudentRequest) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setForm((f) => ({ ...f, [key]: e.target.value }));

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    const clientErrors = validate(form);
    setErrors(clientErrors);
    if (Object.keys(clientErrors).length) return;

    const payload: StudentRequest = {
      ...form,
      name: form.name.trim(),
      email: form.email.trim(),
      department: form.department.trim(),
      branch: form.branch.trim(),
      presentAddress: form.presentAddress?.trim() || undefined,
      permanentAddress: form.permanentAddress?.trim() || undefined,
    };

    setSaving(true);
    setFormError(null);
    try {
      const saved = editing ? await studentsApi.update(student.id, payload) : await studentsApi.create(payload);
      onSaved(saved, !editing);
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 409) setErrors({ email: 'A user with this email already exists' });
        else setErrors(err.fieldErrors);
        setFormError(err.status === 409 ? null : err.message);
      } else {
        setFormError('Something went wrong. Please try again.');
      }
    } finally {
      setSaving(false);
    }
  };

  const emailChanged = editing && form.email.trim() !== student.email;

  return (
    <Modal open={open} onClose={onClose} wide title={editing ? `Edit ${student.name}` : 'Register a student'}
      footer={
        <>
          <button type="button" className="btn-secondary" onClick={onClose}>Cancel</button>
          <button type="submit" form="student-form" className="btn-primary" disabled={saving}>
            {saving && <Spinner />} {editing ? 'Save changes' : 'Register student'}
          </button>
        </>
      }>
      <form id="student-form" onSubmit={handleSubmit} className="grid grid-cols-1 gap-4 sm:grid-cols-2" noValidate>
        {formError && <div className="sm:col-span-2"><Alert>{formError}</Alert></div>}
        <Field label="Full name" required value={form.name} onChange={set('name')} error={errors.name} />
        <Field label="Email" type="email" required value={form.email} onChange={set('email')} error={errors.email}
               hint={emailChanged ? 'Changing the email also changes their login and signs them out.' : undefined} />
        <Field label="Department" required value={form.department} onChange={set('department')}
               error={errors.department} placeholder="e.g. Engineering" />
        <Field label="Branch" required value={form.branch} onChange={set('branch')} error={errors.branch}
               placeholder="e.g. CSE" />
        <Field label="Date of birth" type="date" required max={today()} value={form.dateOfBirth}
               onChange={set('dateOfBirth')} error={errors.dateOfBirth} />
        <div className="hidden sm:block" />
        <Field label="Present address" value={form.presentAddress} onChange={set('presentAddress')}
               error={errors.presentAddress} maxLength={400} />
        <Field label="Permanent address" value={form.permanentAddress} onChange={set('permanentAddress')}
               error={errors.permanentAddress} maxLength={400} />
        {!editing && (
          <p className="text-xs text-slate-500 sm:col-span-2">
            A login account with a temporary password (valid 48 hours) is created for the student.
          </p>
        )}
      </form>
    </Modal>
  );
}
