package com.student.studentservice.entity;

import com.student.studentservice.service.AdminLevel;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@EntityListeners(AuditingEntityListener.class)
@AllArgsConstructor
@NoArgsConstructor
@Data
@Table(name = "admin")
public class Admins {
    @Id
    private Long id;

    @Column(name = "department")
    private String department;

    @Enumerated(EnumType.STRING)
    @Column(name = "admin_level")
    private AdminLevel adminLevel;

    @ToString.Exclude
    @OneToOne
    @MapsId
    @JoinColumn(name = "id")
    private Persons persons;

}
