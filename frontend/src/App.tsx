import { Navigate, Route, Routes } from 'react-router-dom';
import { homePathFor, useAuth } from './auth/AuthContext';
import { RequireAuth } from './auth/RequireAuth';
import { Layout } from './components/Layout';
import { AnnouncementsPage } from './pages/AnnouncementsPage';
import { ChangePasswordPage } from './pages/ChangePasswordPage';
import { CourseDetailPage } from './pages/CourseDetailPage';
import { CoursesPage } from './pages/CoursesPage';
import { DashboardPage } from './pages/DashboardPage';
import { EmailsPage } from './pages/EmailsPage';
import { LoginPage } from './pages/LoginPage';
import { MyCoursesPage } from './pages/MyCoursesPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { ProfilePage } from './pages/ProfilePage';
import { StudentDetailPage } from './pages/StudentDetailPage';
import { StudentsPage } from './pages/StudentsPage';
import { SystemPage } from './pages/SystemPage';

const ADMINS = ['ROLE_ADMIN', 'ROLE_SUPER_ADMIN'] as const;

function Home() {
  const { session } = useAuth();
  return <Navigate to={session ? homePathFor(session) : '/login'} replace />;
}

export function App() {
  return (
    <Routes>
      <Route path="/" element={<Home />} />
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<Layout />}>
          <Route path="/change-password" element={<ChangePasswordPage />} />
          {/* Shared pages render a role-specific view */}
          <Route path="/dashboard" element={<DashboardPage />} />
          <Route path="/courses" element={<CoursesPage />} />
          <Route path="/announcements" element={<AnnouncementsPage />} />
          <Route element={<RequireAuth roles={[...ADMINS]} />}>
            <Route path="/students" element={<StudentsPage />} />
            <Route path="/students/:id" element={<StudentDetailPage />} />
            <Route path="/courses/:id" element={<CourseDetailPage />} />
            <Route path="/emails" element={<EmailsPage />} />
            <Route path="/system" element={<SystemPage />} />
          </Route>
          <Route element={<RequireAuth roles={['ROLE_STUDENT']} />}>
            <Route path="/my-courses" element={<MyCoursesPage />} />
            <Route path="/profile" element={<ProfilePage />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<NotFoundPage />} />
    </Routes>
  );
}
