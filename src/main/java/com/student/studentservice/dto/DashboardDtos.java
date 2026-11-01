package com.student.studentservice.dto;

import com.student.studentservice.dto.AnnouncementDtos.AnnouncementResponse;
import com.student.studentservice.dto.EnrollmentDtos.MyEnrollment;

import java.util.List;

public final class DashboardDtos {

    private DashboardDtos() {
    }

    public record AdminDashboard(
            long totalStudents,
            long totalCourses,
            long activeEnrollments,
            Double averageAttendance,
            List<LowAttendanceEntry> lowAttendance,
            List<CourseFill> courseFill,
            List<DepartmentCount> studentsByDepartment,
            List<AnnouncementResponse> recentAnnouncements
    ) {
    }

    public record LowAttendanceEntry(Long studentId, String studentName, Long courseId, String courseCode,
                                     double percentage) {
    }

    public record CourseFill(Long courseId, String code, String title, long enrolled, int capacity) {
    }

    public record DepartmentCount(String department, long students) {
    }

    public record StudentDashboard(
            String name,
            String department,
            String branch,
            Double cgpa,
            int completedCredits,
            int currentCredits,
            Double overallAttendance,
            long coursesBelowAttendance,
            List<MyEnrollment> currentCourses,
            List<AnnouncementResponse> announcements
    ) {
    }
}
