package com.student.studentservice.repository;

import com.student.studentservice.config.JpaAuditingConfig;
import com.student.studentservice.entity.*;
import com.student.studentservice.service.AccountStatus;
import com.student.studentservice.service.Roles;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.student.studentservice.support.PostgresTestcontainer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real JPA mappings, queries and Specifications against PostgreSQL (Testcontainers), on a
 * schema built by the Flyway migrations and checked by Hibernate (ddl-auto=validate).
 * Skipped when Docker isn't available; CI always runs it.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // the container, not an embedded DB
@Testcontainers(disabledWithoutDocker = true)
@Import({JpaAuditingConfig.class, PostgresTestcontainer.class})
class RepositoryIntegrationTest {

    @Autowired private TestEntityManager em;
    @Autowired private StudentRepository studentRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private LoginSecurityRepository loginSecurityRepository;
    @Autowired private PersonsRepository personsRepository;

    private Student persistStudent(String name, String email, String department, String branch) {
        Persons person = em.persist(new Persons(null, name, email));
        Student student = new Student();
        student.setPersons(person);
        student.setDepartment(department);
        student.setBranch(branch);
        student.setDateOfBirth(LocalDate.of(2003, 1, 1));
        return em.persist(student);
    }

    private User persistUser(Persons person) {
        User user = new User();
        user.setPersons(person);
        user.setEmail(person.getEmail());
        user.setRole(Roles.ROLE_STUDENT);
        return em.persist(user);
    }

    private RefreshToken persistToken(User user, String value) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setToken(value);
        token.setExpiryDate(LocalDateTime.now().plusDays(7));
        token.setIsRevoked(false);
        return em.persist(token);
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("StudentSpecifications")
    class Specifications {

        private Student alice, bob, carol;

        @BeforeEach
        void seed() {
            alice = persistStudent("Alice Kumar", "alice@college.com", "Engineering", "CSE");
            bob = persistStudent("Bob Das", "bob@uni.org", "Engineering", "ECE");
            carol = persistStudent("Carol Mishra", "carol@college.com", "Science", "Physics");
            em.flush();
            em.clear();
        }

        private Page<Student> find(String search, String department, String branch) {
            Specification<Student> spec = Specification.allOf(Stream.of(
                            StudentSpecifications.nameOrEmailContains(search),
                            StudentSpecifications.departmentEquals(department),
                            StudentSpecifications.branchEquals(branch))
                    .filter(Objects::nonNull).toList());
            return studentRepository.findAll(spec, PageRequest.of(0, 10, Sort.by("id")));
        }

        @Test
        void noFilters_returnsEveryone() {
            assertThat(find(null, null, null).getContent()).hasSize(3);
        }

        @Test
        void blankFilters_areIgnored() {
            assertThat(find("  ", "", " ").getContent()).hasSize(3);
        }

        @Test
        void search_matchesNameCaseInsensitively() {
            assertThat(find("KUMAR", null, null).getContent())
                    .extracting(Student::getId).containsExactly(alice.getId());
        }

        @Test
        void search_matchesEmailPartially() {
            assertThat(find("college.com", null, null).getContent())
                    .extracting(Student::getId).containsExactly(alice.getId(), carol.getId());
        }

        @Test
        void department_isExactButCaseInsensitive() {
            assertThat(find(null, " engineering ", null).getContent())
                    .extracting(Student::getId).containsExactly(alice.getId(), bob.getId());
            assertThat(find(null, "Engineer", null).getContent()).isEmpty();
        }

        @Test
        void branch_filter() {
            assertThat(find(null, null, "physics").getContent())
                    .extracting(Student::getId).containsExactly(carol.getId());
        }

        @Test
        void filters_combineWithAnd() {
            assertThat(find("college", "Engineering", "CSE").getContent())
                    .extracting(Student::getId).containsExactly(alice.getId());
            assertThat(find("bob", "Science", null).getContent()).isEmpty();
        }

        @Test
        void sortsByNestedPersonNameAndPaginates() {
            Page<Student> page = studentRepository.findAll(Specification.allOf(),
                    PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "persons.name")));

            assertThat(page.getContent()).extracting(s -> s.getPersons().getName())
                    .containsExactly("Carol Mishra", "Bob Das");
            assertThat(page.getTotalElements()).isEqualTo(3);
            assertThat(page.getTotalPages()).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------
    @Nested
    @DisplayName("mappings and custom queries")
    class Mappings {

        @Test
        void studentSharesPrimaryKeyWithPerson_andAuditFieldsAreSet() {
            Student student = persistStudent("Dan", "dan@x.com", "Arts", "History");
            em.flush();

            assertThat(student.getId()).isEqualTo(student.getPersons().getId());
            assertThat(student.getCreatedAt()).as("@CreatedDate via JPA auditing").isNotNull();
            assertThat(student.getUpdatedAt()).as("@LastModifiedDate via JPA auditing").isNotNull();
        }

        @Test
        void personsLookupByEmail() {
            persistStudent("Eve", "eve@x.com", "Arts", "History");

            assertThat(personsRepository.existsByEmail("eve@x.com")).isTrue();
            assertThat(personsRepository.existsByEmail("nobody@x.com")).isFalse();
            assertThat(personsRepository.findByEmail("eve@x.com")).get()
                    .extracting(Persons::getName).isEqualTo("Eve");
        }

        @Test
        void loginSecurity_isFoundByUserId() {
            Student student = persistStudent("Fay", "fay@x.com", "Arts", "History");
            User user = persistUser(student.getPersons());
            LoginSecurity ls = new LoginSecurity();
            ls.setUser(user);
            ls.setAccountStatus(AccountStatus.ACTIVE);
            em.persist(ls);
            em.flush();
            em.clear();

            assertThat(loginSecurityRepository.findByUserId(user.getId())).isPresent();
            assertThat(loginSecurityRepository.findByUserId(999L)).isEmpty();
        }

        @Test
        void revokeAllTokensByUser_revokesOnlyThatUsersTokens() {
            User fay = persistUser(persistStudent("Fay", "fay@x.com", "Arts", "History").getPersons());
            User gus = persistUser(persistStudent("Gus", "gus@x.com", "Arts", "History").getPersons());
            persistToken(fay, "fay-1");
            persistToken(fay, "fay-2");
            persistToken(gus, "gus-1");
            em.flush();

            refreshTokenRepository.revokeAllTokensByUser(fay);
            em.clear();

            assertThat(refreshTokenRepository.findByToken("fay-1")).get().extracting(RefreshToken::getIsRevoked).isEqualTo(true);
            assertThat(refreshTokenRepository.findByToken("fay-2")).get().extracting(RefreshToken::getIsRevoked).isEqualTo(true);
            assertThat(refreshTokenRepository.findByToken("gus-1")).get().extracting(RefreshToken::getIsRevoked).isEqualTo(false);
        }

        @Test
        void deleteAllByUser_deletesOnlyThatUsersTokens() {
            User fay = persistUser(persistStudent("Fay", "fay@x.com", "Arts", "History").getPersons());
            User gus = persistUser(persistStudent("Gus", "gus@x.com", "Arts", "History").getPersons());
            persistToken(fay, "fay-1");
            persistToken(gus, "gus-1");
            em.flush();

            refreshTokenRepository.deleteAllByUser(fay);
            em.clear();

            assertThat(refreshTokenRepository.findByToken("fay-1")).isEmpty();
            assertThat(refreshTokenRepository.findByToken("gus-1")).isPresent();
        }
    }
}
