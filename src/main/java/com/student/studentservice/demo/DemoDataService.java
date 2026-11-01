package com.student.studentservice.demo;

import com.student.studentservice.entity.*;
import com.student.studentservice.repository.*;
import com.student.studentservice.service.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.student.studentservice.config.CacheConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Seeds (and optionally resets) a realistic demo dataset so visitors can explore the app
 * via "Try as Admin / Try as Student" without credentials. Dates are relative to today so the
 * demo always looks current. Enabled with app.demo.enabled=true.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemoDataService {

    private static final String[] FIRST_NAMES = {
            "Aarav", "Vivaan", "Aditya", "Ananya", "Diya", "Ishaan", "Kavya", "Arjun", "Saanvi", "Rohan",
            "Meera", "Kabir", "Priya", "Aryan", "Nisha", "Rahul", "Sneha", "Vikram", "Pooja", "Siddharth",
            "Riya", "Karan", "Tanvi", "Aditi", "Manav", "Shreya", "Dev", "Neha", "Yash", "Ira",
            "Om", "Lakshmi", "Harsh", "Anika", "Varun", "Simran"};
    private static final String[] LAST_NAMES = {
            "Sharma", "Patel", "Iyer", "Reddy", "Nair", "Gupta", "Das", "Mohanty", "Singh", "Mehta",
            "Rao", "Kulkarni", "Banerjee", "Joshi", "Pillai", "Chopra", "Mishra", "Bose"};

    private record Dept(String name, String branch, int students) {
    }

    private static final List<Dept> DEPARTMENTS = List.of(
            new Dept("Computer Science", "CSE", 18),
            new Dept("Electronics", "ECE", 10),
            new Dept("Mechanical", "ME", 8));

    private record CourseSeed(String code, String title, String department, int credits, int capacity,
                              String instructor, String schedule, String description) {
    }

    private static final List<CourseSeed> PREVIOUS_TERM = List.of(
            new CourseSeed("CS101", "Programming Fundamentals", "Computer Science", 4, 120, "Dr. Ramesh Iyer",
                    "Mon/Wed 09:00–10:30", "Problem solving, control flow, functions and arrays in C."),
            new CourseSeed("MA101", "Engineering Mathematics I", "Mathematics", 4, 150, "Dr. Sunita Rao",
                    "Tue/Thu 09:00–10:30", "Calculus, sequences and series, and linear algebra basics."),
            new CourseSeed("EC101", "Basic Electronics", "Electronics", 3, 120, "Prof. Anil Kumar",
                    "Fri 11:00–13:00", "Diodes, transistors, amplifiers and an introduction to digital circuits."),
            new CourseSeed("HS101", "Communication Skills", "Humanities", 2, 150, "Ms. Farah Khan",
                    "Wed 14:00–16:00", "Technical writing, presentations and professional communication."));

    private static final List<CourseSeed> CURRENT_TERM = List.of(
            new CourseSeed("CS201", "Data Structures & Algorithms", "Computer Science", 4, 60, "Dr. Ramesh Iyer",
                    "Mon/Wed 10:00–11:30", "Lists, trees, graphs, hashing, sorting and algorithm analysis."),
            new CourseSeed("CS202", "Database Management Systems", "Computer Science", 4, 50, "Dr. Meenakshi Pillai",
                    "Tue/Thu 11:00–12:30", "Relational model, SQL, normalization, transactions and indexing."),
            new CourseSeed("CS203", "Object-Oriented Programming with Java", "Computer Science", 3, 40,
                    "Prof. Vikram Sethi", "Fri 09:00–12:00", "Classes, inheritance, interfaces, collections and testing."),
            new CourseSeed("CS301", "Machine Learning", "Computer Science", 4, 18, "Dr. Kavita Menon",
                    "Mon/Thu 14:00–15:30", "Supervised and unsupervised learning, model evaluation and neural networks."),
            new CourseSeed("EC201", "Digital Logic Design", "Electronics", 4, 45, "Prof. Anil Kumar",
                    "Tue/Fri 10:00–11:30", "Boolean algebra, combinational and sequential circuits, FSMs."),
            new CourseSeed("ME201", "Engineering Mechanics", "Mechanical", 4, 40, "Dr. Harish Kulkarni",
                    "Mon/Wed 11:30–13:00", "Statics, dynamics, friction and the analysis of structures."),
            new CourseSeed("MA201", "Probability & Statistics", "Mathematics", 3, 90, "Dr. Sunita Rao",
                    "Tue/Thu 14:00–15:30", "Probability, distributions, estimation and hypothesis testing."),
            new CourseSeed("HS201", "Professional Ethics", "Humanities", 2, 120, "Ms. Farah Khan",
                    "Fri 14:00–16:00", "Ethics in engineering practice, case studies and IP basics."));

    @Value("${app.demo.enabled:false}")
    private boolean enabled;

    @Value("${app.demo.admin-email:demo.admin@studenthub.dev}")
    private String demoAdminEmail;

    @Value("${app.demo.student-email:demo.student@studenthub.dev}")
    private String demoStudentEmail;

    private final PersonsRepository personsRepository;
    private final StudentRepository studentRepository;
    private final AdminsRepository adminsRepository;
    private final UserRepository userRepository;
    private final LoginSecurityRepository loginSecurityRepository;
    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final AttendanceRecordRepository attendanceRepository;
    private final AnnouncementRepository announcementRepository;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager entityManager;

    public boolean isEnabled() {
        return enabled;
    }

    public String demoAdminEmail() {
        return demoAdminEmail;
    }

    public String demoStudentEmail() {
        return demoStudentEmail;
    }

    /** Seeds the dataset if demo mode is on and the catalog is empty. */
    @Transactional
    public void seedIfEmpty() {
        if (!enabled) {
            return;
        }
        ensureDemoAdmin();
        if (courseRepository.count() > 0) {
            log.info("Demo mode: data already present, skipping seed");
            return;
        }
        seed();
    }

    /**
     * Wipes all students and academic data (admins are kept), then reseeds.
     * Intended for dedicated demo deployments only.
     */
    @CacheEvict(allEntries = true, cacheNames = {CacheConfig.COURSE_CATALOG, CacheConfig.ADMIN_DASHBOARD})
    @Transactional
    public void reset() {
        if (!enabled) {
            return;
        }
        log.info("Demo mode: resetting data");
        entityManager.createQuery("DELETE FROM AttendanceRecord").executeUpdate();
        entityManager.createQuery("DELETE FROM Enrollment").executeUpdate();
        entityManager.createQuery("DELETE FROM Announcement").executeUpdate();
        entityManager.createQuery("DELETE FROM Course").executeUpdate();
        String studentUsers = "(SELECT u.id FROM User u WHERE u.role = :role)";
        entityManager.createQuery("DELETE FROM RefreshToken rt WHERE rt.user.id IN " + studentUsers)
                .setParameter("role", Roles.ROLE_STUDENT).executeUpdate();
        entityManager.createQuery("DELETE FROM LoginSecurity ls WHERE ls.id IN " + studentUsers)
                .setParameter("role", Roles.ROLE_STUDENT).executeUpdate();
        entityManager.createQuery("DELETE FROM User u WHERE u.role = :role")
                .setParameter("role", Roles.ROLE_STUDENT).executeUpdate();
        entityManager.createQuery("DELETE FROM Student").executeUpdate();
        entityManager.createQuery("DELETE FROM Persons p WHERE p.id NOT IN (SELECT u.id FROM User u)").executeUpdate();
        entityManager.flush();
        entityManager.clear();
        ensureDemoAdmin();
        seed();
    }

    // ------------------------------------------------------------------ seeding

    private void seed() {
        Random random = new Random(2026); // deterministic: the same demo every time
        LocalDate today = LocalDate.now();
        String currentTerm = (today.getMonthValue() >= 7 ? "Autumn " : "Spring ") + today.getYear();
        String previousTerm = today.getMonthValue() >= 7 ? "Spring " + today.getYear() : "Autumn " + (today.getYear() - 1);

        Map<String, Course> previous = new LinkedHashMap<>();
        PREVIOUS_TERM.forEach(c -> previous.put(c.code(), course(c, previousTerm, false)));
        Map<String, Course> current = new LinkedHashMap<>();
        CURRENT_TERM.forEach(c -> current.put(c.code(), course(c, currentTerm, true)));

        // Students: the first one is the demo student
        List<Student> students = new ArrayList<>();
        int nameIndex = 0;
        for (Dept dept : DEPARTMENTS) {
            for (int i = 0; i < dept.students(); i++, nameIndex++) {
                String first = FIRST_NAMES[nameIndex % FIRST_NAMES.length];
                String last = LAST_NAMES[(nameIndex * 7) % LAST_NAMES.length];
                String email = nameIndex == 0 ? demoStudentEmail
                        : (first + "." + last + nameIndex).toLowerCase() + "@studenthub.dev";
                students.add(student(first + " " + last, email, dept,
                        LocalDate.of(2003 + random.nextInt(3), 1 + random.nextInt(12), 1 + random.nextInt(28))));
            }
        }

        // Previous term: everyone took the common core, graded
        List<Enrollment> enrollments = new ArrayList<>();
        for (Student s : students) {
            List<String> codes = new ArrayList<>(List.of("CS101", "MA101", "HS101"));
            if (!s.getBranch().equals("CSE")) {
                codes.add("EC101");
            }
            double ability = 0.45 + random.nextDouble() * 0.5; // 0.45..0.95
            for (String code : codes) {
                Enrollment e = enrollment(s, previous.get(code), EnrollmentStatus.COMPLETED);
                e.setGrade(gradeFor(ability, random));
                enrollments.add(e);
            }
        }

        // Current term: department courses plus shared electives
        Map<Student, Double> diligence = new IdentityHashMap<>();
        List<Enrollment> currentEnrollments = new ArrayList<>();
        for (Student s : students) {
            List<String> codes = switch (s.getBranch()) {
                case "CSE" -> new ArrayList<>(List.of("CS201", "CS202", "MA201"));
                case "ECE" -> new ArrayList<>(List.of("EC201", "MA201", "CS203"));
                default -> new ArrayList<>(List.of("ME201", "MA201", "HS201"));
            };
            if (s.getBranch().equals("CSE") && random.nextDouble() < 0.85) {
                codes.add("CS301"); // makes Machine Learning nearly full
            }
            if (!codes.contains("HS201") && random.nextDouble() < 0.4) {
                codes.add("HS201");
            }
            // A handful of students struggle with attendance
            diligence.put(s, random.nextDouble() < 0.15 ? 0.55 + random.nextDouble() * 0.15 : 0.80 + random.nextDouble() * 0.18);
            for (String code : codes) {
                Enrollment e = enrollment(s, current.get(code), EnrollmentStatus.ENROLLED);
                currentEnrollments.add(e);
            }
        }
        enrollments.addAll(currentEnrollments);
        enrollmentRepository.saveAll(enrollments);

        // Attendance for the current term: two classes a week for the last 7 weeks
        Student demo = students.get(0);
        List<AttendanceRecord> records = new ArrayList<>();
        for (Enrollment e : currentEnrollments) {
            double rate = diligence.get(e.getStudent());
            if (e.getStudent() == demo) {
                rate = e.getCourse().getCode().equals("CS202") ? 0.68 : 0.93; // one course below 75% for the demo
            }
            for (LocalDate date : classDates(e.getCourse().getCode(), today)) {
                records.add(new AttendanceRecord(e, date, random.nextDouble() < rate));
            }
        }
        attendanceRepository.saveAll(records);

        seedAnnouncements(today);
        log.info("Demo mode: seeded {} courses, {} students, {} enrollments, {} attendance records",
                previous.size() + current.size(), students.size(), enrollments.size(), records.size());
    }

    private void seedAnnouncements(LocalDate today) {
        record Notice(String title, String body, String department, int daysAgo) {
        }
        List<Notice> notices = List.of(
                new Notice("Mid-semester examinations", "Mid-semester exams begin on "
                        + today.plusDays(12).format(java.time.format.DateTimeFormatter.ofPattern("d MMMM"))
                        + ". The detailed timetable is on the notice board. Students below 75% attendance must meet "
                        + "their course coordinator before the exams.", null, 1),
                new Notice("Smart India Hackathon: internal round",
                        "Register your team (4–6 members) with the CS department office by Friday. "
                                + "Mentors will be assigned after shortlisting.", "Computer Science", 3),
                new Notice("Guest lecture: VLSI design careers",
                        "An industry expert from a leading semiconductor company will speak on Thursday at 3 PM in Seminar Hall B.",
                        "Electronics", 5),
                new Notice("Library hours extended",
                        "The central library will stay open until 10 PM on weekdays during the examination period.", null, 8),
                new Notice("Workshop: CAD with SolidWorks",
                        "A two-day hands-on workshop for Mechanical students. Limited seats; register at the department office.",
                        "Mechanical", 10));
        for (Notice n : notices) {
            Announcement a = new Announcement();
            a.setTitle(n.title());
            a.setBody(n.body());
            a.setAudienceDepartment(n.department());
            a.setAuthorName("Academic Office");
            Announcement saved = announcementRepository.saveAndFlush(a);
            // Back-date it so the feed looks realistic (auditing always stamps "now")
            entityManager.createNativeQuery("UPDATE announcements SET created_at = :createdAt WHERE id = :id")
                    .setParameter("createdAt", LocalDateTime.now().minusDays(n.daysAgo()).withHour(10).withMinute(0))
                    .setParameter("id", saved.getId())
                    .executeUpdate();
        }
    }

    /** Two weekday class dates per week over the last 7 weeks, up to yesterday. */
    private static List<LocalDate> classDates(String courseCode, LocalDate today) {
        // Spread courses across different weekday pairs
        int offset = Math.abs(courseCode.hashCode()) % 3;
        Set<DayOfWeek> days = switch (offset) {
            case 0 -> EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY);
            case 1 -> EnumSet.of(DayOfWeek.TUESDAY, DayOfWeek.THURSDAY);
            default -> EnumSet.of(DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);
        };
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = today.minusWeeks(7); d.isBefore(today); d = d.plusDays(1)) {
            if (days.contains(d.getDayOfWeek())) {
                dates.add(d);
            }
        }
        return dates;
    }

    private static Grade gradeFor(double ability, Random random) {
        double score = ability + (random.nextDouble() - 0.5) * 0.25;
        if (score > 0.9) return Grade.O;
        if (score > 0.8) return Grade.A_PLUS;
        if (score > 0.7) return Grade.A;
        if (score > 0.6) return Grade.B_PLUS;
        if (score > 0.5) return Grade.B;
        if (score > 0.42) return Grade.C;
        if (score > 0.36) return Grade.P;
        return Grade.F;
    }

    private Course course(CourseSeed seed, String term, boolean open) {
        Course c = new Course();
        c.setCode(seed.code());
        c.setTitle(seed.title());
        c.setDescription(seed.description());
        c.setDepartment(seed.department());
        c.setCredits(seed.credits());
        c.setCapacity(seed.capacity());
        c.setInstructor(seed.instructor());
        c.setSchedule(seed.schedule());
        c.setTerm(term);
        c.setEnrollmentOpen(open);
        return courseRepository.save(c);
    }

    private Student student(String name, String email, Dept dept, LocalDate dob) {
        Persons person = personsRepository.save(new Persons(null, name, email));
        Student s = new Student();
        s.setPersons(person);
        s.setDepartment(dept.name());
        s.setBranch(dept.branch());
        s.setDateOfBirth(dob);
        s.setPresentAddress("Hostel Block " + (char) ('A' + Math.abs(name.hashCode()) % 4) + ", Bhubaneswar");
        s.setPermanentAddress("Odisha, India");
        Student saved = studentRepository.save(s);
        account(person, Roles.ROLE_STUDENT);
        return saved;
    }

    private Enrollment enrollment(Student s, Course c, EnrollmentStatus status) {
        Enrollment e = new Enrollment();
        e.setStudent(s);
        e.setCourse(c);
        e.setStatus(status);
        return e;
    }

    private void ensureDemoAdmin() {
        if (personsRepository.existsByEmail(demoAdminEmail)) {
            return;
        }
        Persons person = personsRepository.save(new Persons(null, "Demo Admin", demoAdminEmail));
        Admins admin = new Admins();
        admin.setPersons(person);
        admin.setDepartment("Academic Office");
        admin.setAdminLevel(AdminLevel.ADMIN);
        adminsRepository.save(admin);
        account(person, Roles.ROLE_ADMIN);
        log.info("Demo mode: created demo admin {}", demoAdminEmail);
    }

    /** One unusable random hash shared by all demo accounts (hashing per account is slow on small hosts). */
    private String unusablePasswordHash;

    /** Login account with an unusable random password; demo users sign in via /auth/demo-login. */
    private void account(Persons person, Roles role) {
        if (unusablePasswordHash == null) {
            unusablePasswordHash = passwordEncoder.encode(UUID.randomUUID() + "-" + new SecureRandom().nextLong());
        }
        User user = new User();
        user.setPersons(person);
        user.setEmail(person.getEmail());
        user.setRole(role);
        user.setPassword(unusablePasswordHash);
        user.setIsFirstTimeLogin(false);
        user.setIsActive(true);
        user.setIsEmailVerified(true);
        user.setTempPasswordAttemptsCount(0);
        userRepository.save(user);

        LoginSecurity ls = new LoginSecurity();
        ls.setUser(user);
        ls.setAccountStatus(AccountStatus.ACTIVE);
        ls.setWrongPasswordCount(0);
        ls.setPermanentBlock(false);
        loginSecurityRepository.save(ls);
    }
}
