package com.seatsure.repository;

import com.seatsure.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByOwnerAndIdempotencyKey(String owner, String idempotencyKey);

    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    /** 0 when another request already removed it: the caller lost the race to reclaim the key. */
    @Modifying
    @Query("DELETE FROM IdempotencyRecord r WHERE r.id = :id")
    int deleteRecord(@Param("id") Long id);
}
