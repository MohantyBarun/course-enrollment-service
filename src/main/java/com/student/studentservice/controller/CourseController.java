package com.student.studentservice.controller;

import com.student.studentservice.dto.CourseDtos.CourseRequest;
import com.student.studentservice.dto.CourseDtos.CourseResponse;
import com.student.studentservice.dto.EnrollmentDtos.*;
import com.student.studentservice.service.AttendanceService;
import com.student.studentservice.service.CourseService;
import com.student.studentservice.service.EnrollmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/courses")
@RequiredArgsConstructor
@Tag(name = "Courses", description = "Course catalog, enrollment, rosters and attendance")
public class CourseController {

    private final CourseService courseService;
    private final EnrollmentService enrollmentService;
    private final AttendanceService attendanceService;

    // ---------------------------------------------------------------- catalog (everyone)

    @Operation(summary = "Browse the course catalog", description = "`search` matches code, title or instructor.")
    @GetMapping
    public List<CourseResponse> list(@RequestParam(required = false) String search,
                                     @RequestParam(required = false) String department,
                                     @RequestParam(required = false) String term) {
        return courseService.list(search, department, term);
    }

    @Operation(summary = "Get a course")
    @GetMapping("/{id}")
    public CourseResponse get(@PathVariable Long id) {
        return courseService.get(id);
    }

    // ---------------------------------------------------------------- management (admin)

    @Operation(summary = "Create a course (admin)")
    @PostMapping
    public ResponseEntity<CourseResponse> create(@Valid @RequestBody CourseRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(courseService.create(request));
    }

    @Operation(summary = "Update a course (admin)")
    @PutMapping("/{id}")
    public CourseResponse update(@PathVariable Long id, @Valid @RequestBody CourseRequest request) {
        return courseService.update(id, request);
    }

    @Operation(summary = "Delete a course without enrollments (admin)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        courseService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Course roster with grades and attendance (admin)")
    @GetMapping("/{id}/roster")
    public List<RosterEntry> roster(@PathVariable Long id) {
        return enrollmentService.roster(id);
    }

    @Operation(summary = "Attendance sheet for a class date (admin)", description = "Defaults to today.")
    @GetMapping("/{id}/attendance")
    public AttendanceSheet attendanceSheet(@PathVariable Long id,
                                           @RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return attendanceService.sheet(id, date != null ? date : LocalDate.now());
    }

    @Operation(summary = "Record attendance for a class date (admin)", description = "Creates or updates the records.")
    @PostMapping("/{id}/attendance")
    public AttendanceSheet recordAttendance(@PathVariable Long id, @Valid @RequestBody AttendanceRequest request) {
        return attendanceService.record(id, request);
    }

    // ---------------------------------------------------------------- enrollment (student)

    @Operation(summary = "Enroll in a course (student)",
            description = "Fails if enrollment is closed, the course is full, or it would exceed "
                    + EnrollmentService.MAX_CREDITS_PER_TERM + " credits in the term.")
    @PostMapping("/{id}/enroll")
    public ResponseEntity<MyEnrollment> enroll(@PathVariable Long id, @Parameter(hidden = true) Authentication auth) {
        return ResponseEntity.status(HttpStatus.CREATED).body(enrollmentService.enroll(auth.getName(), id));
    }

    @Operation(summary = "Drop a course (student)", description = "Only while enrollment is open.")
    @DeleteMapping("/{id}/enroll")
    public ResponseEntity<Void> drop(@PathVariable Long id, @Parameter(hidden = true) Authentication auth) {
        enrollmentService.drop(auth.getName(), id);
        return ResponseEntity.noContent().build();
    }
}
