package com.student.studentservice.service;

import com.student.studentservice.entity.Course;
import com.student.studentservice.entity.Enrollment;
import com.student.studentservice.entity.Persons;
import com.student.studentservice.entity.Student;

import java.time.LocalDate;

/** Builders for academic entities used across unit tests. */
final class AcademicTestData {

    private AcademicTestData() {
    }

    static Course course(long id, String code, int credits, int capacity, String term, boolean open) {
        Course c = new Course();
        c.setId(id);
        c.setCode(code);
        c.setTitle(code + " title");
        c.setCredits(credits);
        c.setCapacity(capacity);
        c.setDepartment("Computer Science");
        c.setTerm(term);
        c.setEnrollmentOpen(open);
        return c;
    }

    static Student student(long id, String name) {
        Student s = new Student();
        s.setId(id);
        s.setPersons(new Persons(id, name, name.toLowerCase().replace(' ', '.') + "@x.com"));
        s.setDepartment("Computer Science");
        s.setBranch("CSE");
        s.setDateOfBirth(LocalDate.of(2004, 1, 1));
        return s;
    }

    static Enrollment enrollment(long id, Student s, Course c, EnrollmentStatus status, Grade grade) {
        Enrollment e = new Enrollment();
        e.setId(id);
        e.setStudent(s);
        e.setCourse(c);
        e.setStatus(status);
        e.setGrade(grade);
        return e;
    }
}
