package com.student.studentservice.integration;

import com.student.studentservice.dto.CourseDtos.CourseRequest;
import com.student.studentservice.exception.BadRequestException;
import com.student.studentservice.exception.ConflictException;
import com.student.studentservice.service.CourseService;
import com.student.studentservice.service.EnrollmentService;
import com.student.studentservice.service.StudentService;
import com.student.studentservice.support.FullAppIntegrationTest;
import com.student.studentservice.support.TestStudents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Real concurrent transactions against PostgreSQL: the row locks in EnrollmentService must hold. */
@FullAppIntegrationTest
class EnrollmentConcurrencyIntegrationTest {

    private static final String TERM = "Autumn 2026";

    @Autowired private StudentService studentService;
    @Autowired private CourseService courseService;
    @Autowired private EnrollmentService enrollmentService;
    @Autowired private JdbcTemplate jdbc;

    private List<String> registerStudents(int count) {
        String batch = TestStudents.unique("s");
        return IntStream.range(0, count).mapToObj(i -> {
            String email = batch + "-" + i + "@college.test";
            studentService.registerStudent(TestStudents.request(email));
            return email;
        }).toList();
    }

    private Long createCourse(int credits, int capacity) {
        String code = TestStudents.unique("C").toUpperCase().replace("-", "").substring(0, 9);
        return courseService.create(new CourseRequest(code, "Course " + code, null, credits, "Computer Science",
                TERM, capacity, null, null, true)).id();
    }

    /** Starts every task at the same moment and returns what each one threw (null = succeeded). */
    private static <T> List<Throwable> race(List<T> inputs, Function<T, Object> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(inputs.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Throwable>> futures = new ArrayList<>();
        for (T input : inputs) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    task.apply(input);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        start.countDown();
        List<Throwable> outcomes = new ArrayList<>();
        for (Future<Throwable> f : futures) {
            outcomes.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    @Test
    @DisplayName("30 students racing for 5 seats: exactly 5 get in, everyone else is told it's full")
    void lastSeats_areNeverOverbooked() throws Exception {
        List<String> students = registerStudents(30);
        Long courseId = createCourse(4, 5);

        List<Throwable> outcomes = race(students, email -> enrollmentService.enroll(email, courseId));

        assertThat(outcomes.stream().filter(o -> o == null)).hasSize(5);
        assertThat(outcomes.stream().filter(o -> o != null))
                .hasSize(25)
                .allSatisfy(o -> assertThat(o).isInstanceOf(ConflictException.class).hasMessageContaining("full"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM enrollments WHERE course_id = ? AND status = 'ENROLLED'",
                Long.class, courseId)).isEqualTo(5);
    }

    @Test
    @DisplayName("one student enrolling in 8 four-credit courses at once never exceeds 24 credits")
    void creditLimit_holdsUnderConcurrentEnrollments() throws Exception {
        String email = registerStudents(1).get(0);
        List<Long> courses = IntStream.range(0, 8).mapToObj(i -> createCourse(4, 50)).toList();

        List<Throwable> outcomes = race(courses, courseId -> enrollmentService.enroll(email, courseId));

        assertThat(outcomes.stream().filter(o -> o == null)).hasSize(6); // 6 x 4 = 24
        assertThat(outcomes.stream().filter(o -> o != null))
                .allSatisfy(o -> assertThat(o).isInstanceOf(BadRequestException.class).hasMessageContaining("limit"));
        Long credits = jdbc.queryForObject("SELECT COALESCE(SUM(c.credits), 0) FROM enrollments e "
                + "JOIN courses c ON c.id = e.course_id JOIN persons p ON p.id = e.student_id "
                + "WHERE p.email = ? AND e.status = 'ENROLLED'", Long.class, email);
        assertThat(credits).isEqualTo(24);
    }
}
