package com.student.studentservice.service;

import com.student.studentservice.dto.AnnouncementDtos.AnnouncementRequest;
import com.student.studentservice.dto.AnnouncementDtos.AnnouncementResponse;
import com.student.studentservice.entity.Announcement;
import com.student.studentservice.exception.ResourceNotFoundException;
import com.student.studentservice.repository.AnnouncementRepository;
import com.student.studentservice.repository.PersonsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private static final int FEED_SIZE = 50;

    private final AnnouncementRepository announcementRepository;
    private final PersonsRepository personsRepository;
    private final CurrentStudentResolver currentStudent;

    /** Admins see everything; students see general notices plus their department's. */
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> feed(String email, boolean admin, int limit) {
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), FEED_SIZE));
        List<Announcement> items = admin
                ? announcementRepository.findAllByOrderByCreatedAtDesc(page)
                : announcementRepository.findVisibleTo(currentStudent.require(email).getDepartment(), page);
        return items.stream().map(AnnouncementService::toResponse).toList();
    }

    @Transactional
    public AnnouncementResponse create(AnnouncementRequest request, String authorEmail) {
        Announcement a = new Announcement();
        a.setTitle(request.title().trim());
        a.setBody(request.body().trim());
        a.setAudienceDepartment(request.audienceDepartment() == null || request.audienceDepartment().isBlank()
                ? null : request.audienceDepartment().trim());
        a.setAuthorName(personsRepository.findByEmail(authorEmail).map(p -> p.getName()).orElse("Administration"));
        Announcement saved = announcementRepository.save(a);
        log.info("Announcement {} posted by {}", saved.getId(), authorEmail);
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        Announcement a = announcementRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Announcement not found with id: " + id));
        announcementRepository.delete(a);
    }

    static AnnouncementResponse toResponse(Announcement a) {
        return new AnnouncementResponse(a.getId(), a.getTitle(), a.getBody(), a.getAudienceDepartment(),
                a.getAuthorName(), a.getCreatedAt());
    }
}
