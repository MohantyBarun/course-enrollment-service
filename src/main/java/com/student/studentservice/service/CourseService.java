package com.student.studentservice.service;

import com.student.studentservice.config.CacheConfig;
import com.student.studentservice.dto.CourseDtos.CourseRequest;
import com.student.studentservice.dto.CourseDtos.CourseResponse;
import com.student.studentservice.dto.CourseDtos.CourseSummary;
import com.student.studentservice.entity.Course;
import com.student.studentservice.exception.BadRequestException;
import com.student.studentservice.exception.ConflictException;
import com.student.studentservice.exception.ResourceNotFoundException;
import com.student.studentservice.repository.CourseRepository;
import com.student.studentservice.repository.EnrollmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class CourseService {

    /** Statuses that occupy a seat. */
    static final Set<EnrollmentStatus> SEAT_HOLDING = EnumSet.of(EnrollmentStatus.ENROLLED, EnrollmentStatus.COMPLETED);

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;

    /** Cached unless there's a free-text search (unbounded keys, rarely repeated). */
    @Cacheable(cacheNames = CacheConfig.COURSE_CATALOG,
            key = "(#department ?: '').toLowerCase() + '|' + (#term ?: '').toLowerCase()",
            condition = "#search == null || #search.isBlank()")
    @Transactional(readOnly = true)
    public List<CourseResponse> list(String search, String department, String term) {
        List<Specification<Course>> filters = new ArrayList<>();
        if (search != null && !search.isBlank()) {
            String pattern = "%" + search.trim().toLowerCase() + "%";
            filters.add((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("code")), pattern),
                    cb.like(cb.lower(root.get("title")), pattern),
                    cb.like(cb.lower(root.get("instructor")), pattern)));
        }
        if (department != null && !department.isBlank()) {
            filters.add((root, q, cb) -> cb.equal(cb.lower(root.get("department")), department.trim().toLowerCase()));
        }
        if (term != null && !term.isBlank()) {
            filters.add((root, q, cb) -> cb.equal(cb.lower(root.get("term")), term.trim().toLowerCase()));
        }

        Map<Long, Long> seats = seatCounts();
        return courseRepository.findAll(Specification.allOf(filters), Sort.by("code")).stream()
                .map(c -> toResponse(c, seats.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public CourseResponse get(Long id) {
        Course course = find(id);
        return toResponse(course, enrollmentRepository.countByCourseIdAndStatusIn(id, SEAT_HOLDING));
    }

    @CacheEvict(cacheNames = CacheConfig.COURSE_CATALOG, allEntries = true)
    @Transactional
    public CourseResponse create(CourseRequest request) {
        if (courseRepository.existsByCodeIgnoreCase(request.code().trim())) {
            throw new ConflictException("A course with code " + request.code().trim().toUpperCase() + " already exists");
        }
        Course course = new Course();
        apply(request, course);
        Course saved = courseRepository.save(course);
        log.info("Created course {}", saved.getCode());
        return toResponse(saved, 0);
    }

    @CacheEvict(cacheNames = CacheConfig.COURSE_CATALOG, allEntries = true)
    @Transactional
    public CourseResponse update(Long id, CourseRequest request) {
        // Locked so a concurrent enrollment can't take a seat between the capacity check and the save
        Course course = findForUpdate(id);
        if (courseRepository.existsByCodeIgnoreCaseAndIdNot(request.code().trim(), id)) {
            throw new ConflictException("A course with code " + request.code().trim().toUpperCase() + " already exists");
        }
        long enrolled = enrollmentRepository.countByCourseIdAndStatusIn(id, SEAT_HOLDING);
        if (request.capacity() < enrolled) {
            throw new BadRequestException("Capacity can't be lower than the " + enrolled + " students already enrolled");
        }
        apply(request, course);
        return toResponse(course, enrolled);
    }

    @CacheEvict(cacheNames = CacheConfig.COURSE_CATALOG, allEntries = true)
    @Transactional
    public void delete(Long id) {
        Course course = find(id);
        if (enrollmentRepository.existsByCourseId(id)) {
            throw new ConflictException("This course has enrollments and can't be deleted. Close enrollment instead.");
        }
        courseRepository.delete(course);
        log.info("Deleted course {}", course.getCode());
    }

    Course find(Long id) {
        return courseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Course not found with id: " + id));
    }

    /** The course row, locked for the rest of the transaction (see CourseRepository#findByIdForUpdate). */
    Course findForUpdate(Long id) {
        return courseRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Course not found with id: " + id));
    }

    /** Seats taken per course id. */
    Map<Long, Long> seatCounts() {
        Map<Long, Long> seats = new HashMap<>();
        for (Object[] row : enrollmentRepository.countByCourseGrouped(SEAT_HOLDING)) {
            seats.put((Long) row[0], (Long) row[1]);
        }
        return seats;
    }

    static CourseResponse toResponse(Course c, long enrolled) {
        return new CourseResponse(c.getId(), c.getCode(), c.getTitle(), c.getDescription(), c.getCredits(),
                c.getDepartment(), c.getTerm(), c.getCapacity(), enrolled, Math.max(0, c.getCapacity() - enrolled),
                c.getInstructor(), c.getSchedule(), Boolean.TRUE.equals(c.getEnrollmentOpen()));
    }

    static CourseSummary toSummary(Course c) {
        return new CourseSummary(c.getId(), c.getCode(), c.getTitle(), c.getCredits(), c.getTerm(),
                c.getInstructor(), c.getSchedule());
    }

    private static void apply(CourseRequest r, Course c) {
        c.setCode(r.code().trim().toUpperCase());
        c.setTitle(r.title().trim());
        c.setDescription(blankToNull(r.description()));
        c.setCredits(r.credits());
        c.setDepartment(r.department().trim());
        c.setTerm(r.term().trim());
        c.setCapacity(r.capacity());
        c.setInstructor(blankToNull(r.instructor()));
        c.setSchedule(blankToNull(r.schedule()));
        c.setEnrollmentOpen(r.enrollmentOpen() == null || r.enrollmentOpen());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
