package com.seatsure.entity;

/** An entity that belongs to one customer (holds, payments, bookings, waitlist entries). */
public interface OwnedByUser {

    User getUser();

    /** Compares ids only, so a lazy {@code user} proxy isn't loaded. */
    default boolean isOwnedBy(Long userId) {
        return getUser().getId().equals(userId);
    }
}
