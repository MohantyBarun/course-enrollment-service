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
import com.student.studentservice.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StudentServiceImplTest {

    @Mock private StudentRepository studentRepository;
    @Mock private UserRepository userRepository;
    @Mock private LoginSecurityRepository loginSecurityRepository;
    @Mock private PersonsRepository personsRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private AttendanceRecordRepository attendanceRepository;
    @Mock private com.student.studentservice.outbox.OutboxService outbox;

    private final com.student.studentservice.util.SecretBox secretBox =
            new com.student.studentservice.util.SecretBox("", "unit-test-secret-unit-test-secret-unit-test-secret");

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private StudentServiceImpl studentService;

    @BeforeEach
    void setUp() {
        studentService = new StudentServiceImpl(studentRepository, passwordEncoder, userRepository,
                loginSecurityRepository, personsRepository, refreshTokenRepository, enrollmentRepository,
                attendanceRepository, outbox, secretBox);
    }

    private static StudentRequestDTO request(String email) {
        StudentRequestDTO dto = new StudentRequestDTO();
        dto.setName("John Doe");
        dto.setEmail(email);
        dto.setDepartment("Engineering");
        dto.setBranch("CSE");
        dto.setDateOfBirth(LocalDate.of(2003, 5, 14));
        dto.setPresentAddress("Bhubaneswar");
        dto.setPermanentAddress("Cuttack");
        return dto;
    }

    private static Student existingStudent(long id, String email) {
        Persons person = new Persons(id, "Old Name", email);
        Student student = new Student();
        student.setId(id);
        student.setPersons(person);
        student.setDepartment("Science");
        student.setBranch("Physics");
        student.setDateOfBirth(LocalDate.of(2002, 1, 1));
        return student;
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("registerStudent")
    class Register {

        @Test
        void duplicateEmail_throwsAndSavesNothing() {
            when(personsRepository.existsByEmail("john@x.com")).thenReturn(true);

            assertThatThrownBy(() -> studentService.registerStudent(request("john@x.com")))
                    .isInstanceOf(DuplicateEmailException.class);
            verify(personsRepository, never()).save(any());
            verifyNoInteractions(studentRepository, userRepository, loginSecurityRepository, outbox);
        }

        @Test
        void createsPersonStudentStudentAccountAndLoginSecurity() {
            when(personsRepository.existsByEmail("john@x.com")).thenReturn(false);
            when(personsRepository.save(any(Persons.class))).thenAnswer(inv -> {
                Persons p = inv.getArgument(0);
                p.setId(42L);
                return p;
            });
            when(studentRepository.save(any(Student.class))).thenAnswer(inv -> {
                Student s = inv.getArgument(0);
                s.setId(42L);
                return s;
            });

            StudentResponseDTO response = studentService.registerStudent(request("john@x.com"));

            assertThat(response.getId()).isEqualTo(42L);
            assertThat(response.getName()).isEqualTo("John Doe");
            assertThat(response.getEmail()).isEqualTo("john@x.com");
            assertThat(response.getBranch()).isEqualTo("CSE");
            assertThat(response.getPresentAddress()).isEqualTo("Bhubaneswar");

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            User user = userCaptor.getValue();
            assertThat(user.getRole()).as("accounts created here are always students").isEqualTo(Roles.ROLE_STUDENT);
            assertThat(user.getEmail()).isEqualTo("john@x.com");
            assertThat(user.getIsFirstTimeLogin()).isTrue();
            assertThat(user.getPassword()).isNull();
            assertThat(user.getTempPassword()).as("temp password must be stored hashed").startsWith("$2");
            assertThat(response.getTemporaryPassword()).as("plain temp password returned once to the admin")
                    .hasSize(8)
                    .satisfies(plain -> assertThat(passwordEncoder.matches(plain, user.getTempPassword())).isTrue());
            assertThat(user.getTempPasswordExpiry())
                    .isBetween(LocalDateTime.now().plusHours(47), LocalDateTime.now().plusHours(49));

            ArgumentCaptor<LoginSecurity> lsCaptor = ArgumentCaptor.forClass(LoginSecurity.class);
            verify(loginSecurityRepository).save(lsCaptor.capture());
            assertThat(lsCaptor.getValue().getUser()).isSameAs(user);
            assertThat(lsCaptor.getValue().getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(lsCaptor.getValue().getPermanentBlock()).isFalse();

            var event = ArgumentCaptor.forClass(com.student.studentservice.outbox.OutboxEvents.StudentRegistered.class);
            verify(outbox).record(eq("Student"), eq(42L),
                    eq(com.student.studentservice.outbox.OutboxEvents.STUDENT_REGISTERED), event.capture());
            assertThat(event.getValue().email()).isEqualTo("john@x.com");
            assertThat(event.getValue().encryptedTempPassword())
                    .as("the queued password is encrypted, never plain text")
                    .isNotEqualTo(response.getTemporaryPassword());
            assertThat(secretBox.decrypt(event.getValue().encryptedTempPassword()))
                    .isEqualTo(response.getTemporaryPassword());
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("getStudents")
    class GetStudents {

        @ParameterizedTest(name = "page={0}, size={1}, sortBy={2}, direction={3}")
        @CsvSource({
                "-1, 10,  id,       asc",   // negative page
                "0,  0,   id,       asc",   // size too small
                "0,  101, id,       asc",   // size too large
                "0,  10,  password, asc",   // sort field not whitelisted
                "0,  10,  id,       upward" // invalid direction
        })
        void invalidPagingOrSorting_throwsBadRequest(int page, int size, String sortBy, String direction) {
            assertThatThrownBy(() -> studentService.getStudents(null, null, null, page, size, sortBy, direction))
                    .isInstanceOf(BadRequestException.class);
            verifyNoInteractions(studentRepository);
        }

        @Test
        @SuppressWarnings("unchecked")
        void mapsApiSortFieldToEntityPathAndReturnsPagedResult() {
            Student student = existingStudent(7L, "a@x.com");
            when(studentRepository.findAll(any(Specification.class), any(Pageable.class)))
                    .thenAnswer(inv -> new PageImpl<>(List.of(student), inv.getArgument(1), 21));

            PagedResponseDTO<StudentResponseDTO> result =
                    studentService.getStudents("john", "Engineering", null, 2, 5, "name", "desc");

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(studentRepository).findAll(any(Specification.class), pageable.capture());
            assertThat(pageable.getValue())
                    .isEqualTo(PageRequest.of(2, 5, Sort.by(Sort.Direction.DESC, "persons.name")));

            assertThat(result.content()).extracting(StudentResponseDTO::getEmail).containsExactly("a@x.com");
            assertThat(result.page()).isEqualTo(2);
            assertThat(result.size()).isEqualTo(5);
            assertThat(result.totalElements()).isEqualTo(21);
            assertThat(result.totalPages()).isEqualTo(5);
            assertThat(result.last()).isFalse();
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("get by id / email")
    class GetOne {

        @Test
        void getById_returnsAllFields() {
            when(studentRepository.findById(7L)).thenReturn(Optional.of(existingStudent(7L, "a@x.com")));

            StudentResponseDTO dto = studentService.getStudentById(7L);

            assertThat(dto.getTemporaryPassword()).as("only the create response carries it").isNull();
            assertThat(dto.getId()).isEqualTo(7L);
            assertThat(dto.getName()).isEqualTo("Old Name");
            assertThat(dto.getEmail()).isEqualTo("a@x.com");
            assertThat(dto.getDepartment()).isEqualTo("Science");
        }

        @Test
        void getById_unknown_throwsNotFound() {
            when(studentRepository.findById(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> studentService.getStudentById(7L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void getByEmail_returnsStudentProfile() {
            Student student = existingStudent(7L, "a@x.com");
            when(personsRepository.findByEmail("a@x.com")).thenReturn(Optional.of(student.getPersons()));
            when(studentRepository.findById(7L)).thenReturn(Optional.of(student));

            assertThat(studentService.getStudentByEmail("a@x.com").getId()).isEqualTo(7L);
        }

        @Test
        void getByEmail_unknownEmail_throwsNotFound() {
            when(personsRepository.findByEmail("ghost@x.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> studentService.getStudentByEmail("ghost@x.com"))
                    .isInstanceOf(ResourceNotFoundException.class);
            verifyNoInteractions(studentRepository);
        }

        @Test
        void getByEmail_personWithoutStudentProfile_throwsNotFound() {
            // e.g. an admin calling /students/me
            when(personsRepository.findByEmail("admin@x.com")).thenReturn(Optional.of(new Persons(1L, "Admin", "admin@x.com")));
            when(studentRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> studentService.getStudentByEmail("admin@x.com"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("updateStudent")
    class Update {

        @Test
        void unknownId_throwsNotFound() {
            when(studentRepository.findById(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> studentService.updateStudent(7L, request("a@x.com")))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void sameEmail_updatesFieldsWithoutTouchingAccountOrTokens() {
            Student student = existingStudent(7L, "a@x.com");
            when(studentRepository.findById(7L)).thenReturn(Optional.of(student));

            StudentResponseDTO dto = studentService.updateStudent(7L, request("a@x.com"));

            assertThat(dto.getName()).isEqualTo("John Doe");
            assertThat(dto.getDepartment()).isEqualTo("Engineering");
            assertThat(dto.getBranch()).isEqualTo("CSE");
            assertThat(dto.getDateOfBirth()).isEqualTo(LocalDate.of(2003, 5, 14));
            assertThat(student.getUpdatedAt()).isNotNull();
            verify(personsRepository, never()).existsByEmail(any());
            verifyNoInteractions(userRepository, refreshTokenRepository);
        }

        @Test
        void emailTakenByAnotherPerson_throwsDuplicate() {
            when(studentRepository.findById(7L)).thenReturn(Optional.of(existingStudent(7L, "a@x.com")));
            when(personsRepository.existsByEmail("taken@x.com")).thenReturn(true);

            assertThatThrownBy(() -> studentService.updateStudent(7L, request("taken@x.com")))
                    .isInstanceOf(DuplicateEmailException.class);
            verifyNoInteractions(userRepository, refreshTokenRepository);
        }

        @Test
        void emailChanged_syncsLoginEmailAndRevokesSessions() {
            Student student = existingStudent(7L, "a@x.com");
            User user = new User();
            user.setId(7L);
            user.setEmail("a@x.com");
            when(studentRepository.findById(7L)).thenReturn(Optional.of(student));
            when(personsRepository.existsByEmail("new@x.com")).thenReturn(false);
            when(userRepository.findById(7L)).thenReturn(Optional.of(user));

            StudentResponseDTO dto = studentService.updateStudent(7L, request("new@x.com"));

            assertThat(dto.getEmail()).isEqualTo("new@x.com");
            assertThat(student.getPersons().getEmail()).isEqualTo("new@x.com");
            assertThat(user.getEmail()).isEqualTo("new@x.com");
            verify(refreshTokenRepository).revokeAllTokensByUser(user);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("deleteStudent")
    class Delete {

        @Test
        void unknownId_throwsNotFoundAndDeletesNothing() {
            when(studentRepository.findById(7L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> studentService.deleteStudent(7L))
                    .isInstanceOf(ResourceNotFoundException.class);
            verify(studentRepository, never()).delete(any(Student.class));
            verifyNoInteractions(userRepository, refreshTokenRepository, loginSecurityRepository);
        }

        @Test
        void deletesChildRowsBeforeParents() {
            Student student = existingStudent(7L, "a@x.com");
            User user = new User();
            user.setId(7L);
            LoginSecurity loginSecurity = new LoginSecurity();
            when(studentRepository.findById(7L)).thenReturn(Optional.of(student));
            when(userRepository.findById(7L)).thenReturn(Optional.of(user));
            when(loginSecurityRepository.findByUserId(7L)).thenReturn(Optional.of(loginSecurity));

            studentService.deleteStudent(7L);

            // attendance -> enrollments -> refresh_tokens -> login_security -> users -> students -> persons
            InOrder order = inOrder(attendanceRepository, enrollmentRepository, refreshTokenRepository,
                    loginSecurityRepository, userRepository, studentRepository, personsRepository);
            order.verify(attendanceRepository).deleteAllByStudentId(7L);
            order.verify(enrollmentRepository).deleteAllByStudentId(7L);
            order.verify(refreshTokenRepository).deleteAllByUser(user);
            order.verify(loginSecurityRepository).delete(loginSecurity);
            order.verify(userRepository).delete(user);
            order.verify(studentRepository).delete(student);
            order.verify(personsRepository).delete(student.getPersons());
        }

        @Test
        void demoStudent_cannotBeDeleted() {
            ReflectionTestUtils.setField(studentService, "demoStudentEmail", "demo.student@studenthub.dev");
            when(studentRepository.findById(7L)).thenReturn(Optional.of(existingStudent(7L, "demo.student@studenthub.dev")));

            assertThatThrownBy(() -> studentService.deleteStudent(7L))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("demo student");
            verify(studentRepository, never()).delete(any(Student.class));
        }

        @Test
        void demoStudent_emailCannotBeChanged() {
            ReflectionTestUtils.setField(studentService, "demoStudentEmail", "demo.student@studenthub.dev");
            when(studentRepository.findById(7L)).thenReturn(Optional.of(existingStudent(7L, "demo.student@studenthub.dev")));

            assertThatThrownBy(() -> studentService.updateStudent(7L, request("new@x.com")))
                    .isInstanceOf(BadRequestException.class);
        }
    }
}
