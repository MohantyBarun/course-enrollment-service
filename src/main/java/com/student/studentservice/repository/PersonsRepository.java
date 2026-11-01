package com.student.studentservice.repository;

import com.student.studentservice.entity.Persons;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PersonsRepository extends JpaRepository<Persons, Long> {
    boolean existsByEmail(String email);
    Optional<Persons> findByEmail(String email); // find person by email

}
