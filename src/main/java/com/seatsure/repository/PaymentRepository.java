package com.seatsure.repository;

import com.seatsure.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p JOIN FETCH p.hold WHERE p.gatewayRef = :ref")
    Optional<Payment> findByGatewayRefForUpdate(@Param("ref") String ref);

    Optional<Payment> findByGatewayRef(String gatewayRef);

    /**
     * [holdId, showId] for a payment. Scalars on purpose: loading the entities before locking them
     * would leave stale copies in the persistence context that a later FOR UPDATE doesn't refresh.
     */
    @Query("SELECT p.hold.id, p.hold.show.id FROM Payment p WHERE p.gatewayRef = :ref")
    List<Object[]> findHoldAndShowIds(@Param("ref") String ref);

    List<Payment> findByHoldIdOrderByCreatedAtDesc(Long holdId);

    @Query("SELECT p FROM Payment p WHERE p.hold.id = :holdId AND p.status = com.seatsure.entity.Payment.Status.PENDING")
    Optional<Payment> findPending(@Param("holdId") Long holdId);
}
