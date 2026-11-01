package com.student.studentservice.service;

import com.student.studentservice.entity.Persons;
import com.student.studentservice.entity.Student;
import com.student.studentservice.exception.ResourceNotFoundException;
import com.student.studentservice.repository.PersonsRepository;
import com.student.studentservice.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Maps the authenticated user's email (the JWT subject) to their Student record. */
@Component
@RequiredArgsConstructor
public class CurrentStudentResolver {

    private final PersonsRepository personsRepository;
    private final StudentRepository studentRepository;

    public Student require(String email) {
        Persons person = personsRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("No profile found for: " + email));
        return studentRepository.findById(person.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No student profile found for: " + email));
    }
}
