package com.student.studentservice.service;

import com.student.studentservice.dto.PagedResponseDTO;
import com.student.studentservice.dto.StudentRequestDTO;
import com.student.studentservice.dto.StudentResponseDTO;
import com.student.studentservice.entity.LoginSecurity;
import com.student.studentservice.entity.Persons;
import com.student.studentservice.entity.Student;
import com.student.studentservice.entity.User;
import com.student.studentservice.exception.BadRequestException;
import com.student.studentservice.exception.DuplicateEmailException;
import com.student.studentservice.exception.ResourceNotFoundException;
import com.student.studentservice.mapper.StudentMapper;
import com.student.studentservice.outbox.OutboxEvents;
import com.student.studentservice.outbox.OutboxService;
import com.student.studentservice.util.SecretBox;
import com.student.studentservice.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudentServiceImpl implements StudentService {

    static final int MAX_PAGE_SIZE = 100;
    static final int TEMP_PASSWORD_VALID_HOURS = 48;

    // API sort field -> entity property path. Anything not listed here is rejected,
    // so clients can't trigger errors by sorting on arbitrary/unknown properties.
    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "id", "id",
            "name", "persons.name",
            "email", "persons.email",
            "department", "department",
            "branch", "branch",
            "dateOfBirth", "dateOfBirth",
            "createdAt", "createdAt"
    );

    private static final String TEMP_PASSWORD_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789@#$!";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StudentRepository studentRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final LoginSecurityRepository loginSecurityRepository;
    private final PersonsRepository personsRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final AttendanceRecordRepository attendanceRepository;
    private final OutboxService outbox;
    private final SecretBox secretBox;

    /** The demo student powers the "Try as Student" button, so it can't be deleted or re-emailed. */
    @org.springframework.beans.factory.annotation.Value("${app.demo.student-email:}")
    private String demoStudentEmail;

    @Transactional
    @Override
    public StudentResponseDTO registerStudent(StudentRequestDTO requestDTO) {
        log.info("Attempting to register student with email: {}", requestDTO.getEmail());

        if (personsRepository.existsByEmail(requestDTO.getEmail())) {
            throw new DuplicateEmailException(requestDTO.getEmail());
        }

        Persons person = new Persons();
        person.setName(requestDTO.getName());
        person.setEmail(requestDTO.getEmail());
        Persons savedPerson = personsRepository.save(person);

        Student student = new Student();
        StudentMapper.applyStudentFields(requestDTO, student);
        student.setPersons(savedPerson);
        Student savedStudent = studentRepository.save(student);

        String tempPassword = generateTempPassword();

        User user = new User();
        user.setEmail(savedPerson.getEmail());
        user.setRole(Roles.ROLE_STUDENT);
        user.setTempPassword(passwordEncoder.encode(tempPassword));
        user.setTempPasswordExpiry(LocalDateTime.now().plusHours(TEMP_PASSWORD_VALID_HOURS));
        user.setTempPasswordAttemptsCount(0);
        user.setIsFirstTimeLogin(true);
        user.setIsActive(true);
        user.setIsEmailVerified(false);
        user.setPersons(savedPerson);
        user.setPassword(null);
        userRepository.save(user);

        LoginSecurity loginSecurity = new LoginSecurity();
        loginSecurity.setAccountStatus(AccountStatus.ACTIVE);
        loginSecurity.setWrongPasswordCount(0);
        loginSecurity.setPermanentBlock(false);
        loginSecurity.setUser(user);
        loginSecurityRepository.save(loginSecurity);

        log.info("Student registered successfully with id: {}", savedPerson.getId());

        // Welcome email with the temporary password, sent asynchronously via the outbox: it commits
        // with the student (no email for a rolled-back registration, none lost if SMTP is down).
        outbox.record("Student", savedPerson.getId(), OutboxEvents.STUDENT_REGISTERED,
                new OutboxEvents.StudentRegistered(savedPerson.getId(), savedPerson.getName(), savedPerson.getEmail(),
                        secretBox.encrypt(tempPassword), TEMP_PASSWORD_VALID_HOURS));

        // Also returned once to the registering admin (never logged or stored in plain text).
        StudentResponseDTO response = StudentMapper.toResponse(savedStudent);
        response.setTemporaryPassword(tempPassword);
        return response;
    }

    @Transactional(readOnly = true)
    @Override
    public PagedResponseDTO<StudentResponseDTO> getStudents(String search, String department, String branch,
                                                            int page, int size, String sortBy, String direction) {
        if (page < 0) {
            throw new BadRequestException("Page index cannot be negative");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BadRequestException("Page size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String sortProperty = SORTABLE_FIELDS.get(sortBy);
        if (sortProperty == null) {
            throw new BadRequestException("Cannot sort by '" + sortBy + "'. Allowed: " + SORTABLE_FIELDS.keySet());
        }
        Sort.Direction sortDirection = Sort.Direction.fromOptionalString(direction)
                .orElseThrow(() -> new BadRequestException("Sort direction must be 'asc' or 'desc'"));

        Specification<Student> spec = Specification.allOf(Stream.of(
                        StudentSpecifications.nameOrEmailContains(search),
                        StudentSpecifications.departmentEquals(department),
                        StudentSpecifications.branchEquals(branch))
                .filter(Objects::nonNull)
                .toList());

        Page<StudentResponseDTO> result = studentRepository
                .findAll(spec, PageRequest.of(page, size, Sort.by(sortDirection, sortProperty)))
                .map(StudentMapper::toResponse);

        return PagedResponseDTO.from(result);
    }

    @Transactional(readOnly = true)
    @Override
    public StudentResponseDTO getStudentById(Long id) {
        return StudentMapper.toResponse(findStudent(id));
    }

    @Transactional(readOnly = true)
    @Override
    public StudentResponseDTO getStudentByEmail(String email) {
        Persons person = personsRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("No profile found for: " + email));
        Student student = studentRepository.findById(person.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No student profile found for: " + email));
        return StudentMapper.toResponse(student);
    }

    @Transactional
    @Override
    public StudentResponseDTO updateStudent(Long id, StudentRequestDTO requestDTO) {
        log.info("Updating student with id: {}", id);
        Student student = findStudent(id);
        Persons person = student.getPersons();

        boolean emailChanged = !person.getEmail().equals(requestDTO.getEmail());
        if (emailChanged && isDemoStudent(person.getEmail())) {
            throw new BadRequestException("The demo student's email can't be changed");
        }
        if (emailChanged && personsRepository.existsByEmail(requestDTO.getEmail())) {
            throw new DuplicateEmailException(requestDTO.getEmail());
        }

        person.setName(requestDTO.getName());
        person.setEmail(requestDTO.getEmail());
        StudentMapper.applyStudentFields(requestDTO, student);
        // Bump updatedAt even when only Persons fields (name/email) changed.
        student.setUpdatedAt(LocalDateTime.now());

        if (emailChanged) {
            // The login email lives on User too; keep it in sync and force a fresh login,
            // since existing tokens were issued for the old email.
            userRepository.findById(id).ifPresent(user -> {
                user.setEmail(requestDTO.getEmail());
                refreshTokenRepository.revokeAllTokensByUser(user);
            });
            log.info("Email changed for student {}; refresh tokens revoked", id);
        }

        return StudentMapper.toResponse(student);
    }

    @Transactional
    @Override
    public void deleteStudent(Long id) {
        log.info("Deleting student with id: {}", id);
        Student student = findStudent(id);
        if (isDemoStudent(student.getPersons().getEmail())) {
            throw new BadRequestException("The demo student account can't be deleted");
        }

        // Delete children before parents to satisfy foreign keys:
        // attendance -> enrollments -> refresh_tokens -> login_security -> users -> students -> persons
        attendanceRepository.deleteAllByStudentId(id);
        enrollmentRepository.deleteAllByStudentId(id);
        userRepository.findById(id).ifPresent(user -> {
            refreshTokenRepository.deleteAllByUser(user);
            loginSecurityRepository.findByUserId(id).ifPresent(loginSecurityRepository::delete);
            userRepository.delete(user);
        });
        Persons person = student.getPersons();
        studentRepository.delete(student);
        personsRepository.delete(person);

        log.info("Student {} and associated account deleted", id);
    }

    private boolean isDemoStudent(String email) {
        return demoStudentEmail != null && !demoStudentEmail.isBlank() && demoStudentEmail.equalsIgnoreCase(email);
    }

    private Student findStudent(Long id) {
        return studentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Student not found with id: " + id));
    }

    private String generateTempPassword() {
        // generates a random 8 character password like "Xk9#mP2q"
        StringBuilder tempPassword = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            tempPassword.append(TEMP_PASSWORD_CHARS.charAt(RANDOM.nextInt(TEMP_PASSWORD_CHARS.length())));
        }
        return tempPassword.toString();
    }
}
