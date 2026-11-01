package com.student.studentservice.controller;

import com.student.studentservice.dto.AnnouncementDtos.AnnouncementRequest;
import com.student.studentservice.dto.AnnouncementDtos.AnnouncementResponse;
import com.student.studentservice.dto.DashboardDtos.AdminDashboard;
import com.student.studentservice.dto.DashboardDtos.StudentDashboard;
import com.student.studentservice.dto.EnrollmentDtos.AcademicRecord;
import com.student.studentservice.dto.EnrollmentDtos.GradeRequest;
import com.student.studentservice.dto.EnrollmentDtos.RosterEntry;
import com.student.studentservice.service.AnnouncementService;
import com.student.studentservice.service.DashboardService;
import com.student.studentservice.service.EnrollmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Grades, academic records, announcements and dashboards. */
@RestController
@RequiredArgsConstructor
@Tag(name = "Academics", description = "Grades, academic records, announcements and dashboards")
public class AcademicsController {

    private final EnrollmentService enrollmentService;
    private final AnnouncementService announcementService;
    private final DashboardService dashboardService;

    @Operation(summary = "My academic record: courses, grades, attendance, SGPA and CGPA (student)")
    @GetMapping("/students/me/academics")
    public AcademicRecord myRecord(@Parameter(hidden = true) Authentication auth) {
        return enrollmentService.myRecord(auth.getName());
    }

    @Operation(summary = "A student's academic record (admin)")
    @GetMapping("/students/{id}/academics")
    public AcademicRecord recordOf(@PathVariable Long id) {
        return enrollmentService.recordOf(id);
    }

    @Operation(summary = "Assign a grade (admin)",
            description = "10-point scale: O, A+, A, B+, B, C, P, F. Marks the enrollment as completed.")
    @PutMapping("/enrollments/{id}/grade")
    public RosterEntry grade(@PathVariable Long id, @Valid @RequestBody GradeRequest request) {
        return enrollmentService.assignGrade(id, request.grade());
    }

    @Operation(summary = "Announcements feed",
            description = "Students see general notices plus their department's; admins see all.")
    @GetMapping("/announcements")
    public List<AnnouncementResponse> announcements(@RequestParam(defaultValue = "50") int limit,
                                                    @Parameter(hidden = true) Authentication auth) {
        return announcementService.feed(auth.getName(), isAdmin(auth), limit);
    }

    @Operation(summary = "Post an announcement (admin)", description = "Leave audienceDepartment blank for everyone.")
    @PostMapping("/announcements")
    public ResponseEntity<AnnouncementResponse> post(@Valid @RequestBody AnnouncementRequest request,
                                                     @Parameter(hidden = true) Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(announcementService.create(request, auth.getName()));
    }

    @Operation(summary = "Delete an announcement (admin)")
    @DeleteMapping("/announcements/{id}")
    public ResponseEntity<Void> deleteAnnouncement(@PathVariable Long id) {
        announcementService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Admin dashboard")
    @GetMapping("/dashboard/admin")
    public AdminDashboard adminDashboard(@Parameter(hidden = true) Authentication auth) {
        return dashboardService.admin(auth.getName());
    }

    @Operation(summary = "Student dashboard")
    @GetMapping("/dashboard/student")
    public StudentDashboard studentDashboard(@Parameter(hidden = true) Authentication auth) {
        return dashboardService.student(auth.getName());
    }

    private static boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("ROLE_ADMIN") || a.equals("ROLE_SUPER_ADMIN"));
    }
}
