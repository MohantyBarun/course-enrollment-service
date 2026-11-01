package com.student.studentservice.repository;

import com.student.studentservice.entity.Admins;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminsRepository extends JpaRepository<Admins, Long> {
}
