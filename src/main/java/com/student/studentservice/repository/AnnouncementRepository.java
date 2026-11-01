package com.student.studentservice.repository;

import com.student.studentservice.entity.Announcement;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    List<Announcement> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Announcements for everyone plus those targeted at the given department. */
    @Query("SELECT a FROM Announcement a WHERE a.audienceDepartment IS NULL "
            + "OR LOWER(a.audienceDepartment) = LOWER(:department) ORDER BY a.createdAt DESC")
    List<Announcement> findVisibleTo(@Param("department") String department, Pageable pageable);
}
