package com.student.studentservice.controller;

import com.student.studentservice.dto.PagedResponseDTO;
import com.student.studentservice.dto.StudentRequestDTO;
import com.student.studentservice.dto.StudentResponseDTO;
import com.student.studentservice.service.StudentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@Slf4j
@RestController
@RequestMapping("/students")
@RequiredArgsConstructor
@Tag(name = "Students", description = "Student management (admin) and own profile (student)")
public class StudentController {
    private final StudentService studentService;

    @Operation(summary = "Register a student (admin)",
            description = "Creates the student and their login account. The response includes a one-time "
                    + "`temporaryPassword` to give to the student; it is never shown again.")
    @PostMapping
    public ResponseEntity<StudentResponseDTO> createStudent(@Valid @RequestBody StudentRequestDTO requestDTO) {
        log.info("POST /students called");
        StudentResponseDTO created = studentService.registerStudent(requestDTO);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @Operation(summary = "List students (admin)",
            description = "Paginated. `search` matches name or email; `sortBy` is one of "
                    + "id, name, email, department, branch, dateOfBirth, createdAt.")
    @GetMapping
    public ResponseEntity<PagedResponseDTO<StudentResponseDTO>> getStudents(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String branch,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String direction) {
        log.info("GET /students called (page={}, size={})", page, size);
        return ResponseEntity.ok(
                studentService.getStudents(search, department, branch, page, size, sortBy, direction));
    }

    @Operation(summary = "Get my profile (student)")
    @GetMapping("/me")
    public ResponseEntity<StudentResponseDTO> getMyProfile(@Parameter(hidden = true) Authentication authentication) {
        log.info("GET /students/me called by {}", authentication.getName());
        return ResponseEntity.ok(studentService.getStudentByEmail(authentication.getName()));
    }

    @Operation(summary = "Get a student (admin)")
    @GetMapping("/{id}")
    public ResponseEntity<StudentResponseDTO> getStudentById(@PathVariable Long id) {
        log.info("GET /students/{} called", id);
        return ResponseEntity.ok(studentService.getStudentById(id));
    }

    @Operation(summary = "Update a student (admin)",
            description = "Changing the email also changes the login email and signs the student out everywhere.")
    @PutMapping("/{id}")
    public ResponseEntity<StudentResponseDTO> updateStudent(@PathVariable Long id,
                                                            @Valid @RequestBody StudentRequestDTO requestDTO) {
        log.info("PUT /students/{} called", id);
        return ResponseEntity.ok(studentService.updateStudent(id, requestDTO));
    }

    @Operation(summary = "Delete a student and their account (admin)")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStudent(@PathVariable Long id) {
        log.info("DELETE /students/{} called", id);
        studentService.deleteStudent(id);
        return ResponseEntity.noContent().build();
    }
}
