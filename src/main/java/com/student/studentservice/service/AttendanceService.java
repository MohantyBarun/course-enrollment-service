package com.student.studentservice.service;

import com.student.studentservice.dto.EnrollmentDtos.*;
import com.student.studentservice.entity.AttendanceRecord;
import com.student.studentservice.entity.Enrollment;
import com.student.studentservice.exception.BadRequestException;
import com.student.studentservice.repository.AttendanceRecordRepository;
import com.student.studentservice.repository.EnrollmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceService {

    private final AttendanceRecordRepository attendanceRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CourseService courseService;

    /** The roster for a class date, with whatever has already been recorded for it. */
    @Transactional(readOnly = true)
    public AttendanceSheet sheet(Long courseId, LocalDate date) {
        courseService.find(courseId);
        Map<Long, Boolean> recorded = attendanceRepository.findByEnrollmentCourseIdAndClassDate(courseId, date).stream()
                .collect(Collectors.toMap(a -> a.getEnrollment().getId(), AttendanceRecord::getPresent));

        List<AttendanceSheetRow> rows = activeRoster(courseId).stream()
                .map(e -> new AttendanceSheetRow(e.getId(), e.getStudent().getId(), e.getStudent().getPersons().getName(),
                        e.getStudent().getBranch(), recorded.get(e.getId())))
                .sorted(Comparator.comparing(AttendanceSheetRow::studentName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new AttendanceSheet(date, rows, attendanceRepository.findClassDates(courseId));
    }

    /** Creates or updates attendance for one class date. */
    @Transactional
    public AttendanceSheet record(Long courseId, AttendanceRequest request) {
        courseService.find(courseId);
        Map<Long, Enrollment> roster = activeRoster(courseId).stream()
                .collect(Collectors.toMap(Enrollment::getId, Function.identity()));

        Map<Long, AttendanceRecord> existing = attendanceRepository
                .findByEnrollmentCourseIdAndClassDate(courseId, request.date()).stream()
                .collect(Collectors.toMap(a -> a.getEnrollment().getId(), Function.identity()));

        List<AttendanceRecord> toSave = new ArrayList<>();
        for (AttendanceMark mark : request.records()) {
            Enrollment enrollment = roster.get(mark.enrollmentId());
            if (enrollment == null) {
                throw new BadRequestException("Enrollment " + mark.enrollmentId() + " is not on this course's roster");
            }
            AttendanceRecord record = existing.get(mark.enrollmentId());
            if (record == null) {
                toSave.add(new AttendanceRecord(enrollment, request.date(), mark.present()));
            } else {
                record.setPresent(mark.present());
            }
        }
        attendanceRepository.saveAll(toSave);
        log.info("Recorded attendance for course {} on {} ({} students)", courseId, request.date(), request.records().size());
        return sheet(courseId, request.date());
    }

    /** Only students currently taking the course can be marked (completed ones keep their history). */
    private List<Enrollment> activeRoster(Long courseId) {
        return enrollmentRepository.findByCourseIdAndStatusIn(courseId, EnumSet.of(EnrollmentStatus.ENROLLED));
    }
}
