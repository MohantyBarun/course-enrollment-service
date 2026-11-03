package com.seatsure.service;

import java.util.random.RandomGenerator;

/**
 * Booking references: "SS" + 8 characters without look-alikes (0/O, 1/I/L, U), about 6.5e11
 * combinations. Shared by real checkout and the demo seed, so both produce the same format.
 */
public final class BookingReferences {

    private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 8;

    private BookingReferences() {
    }

    /** A random reference; callers check uniqueness (the database's unique constraint is the backstop). */
    public static String next(RandomGenerator random) {
        StringBuilder ref = new StringBuilder("SS");
        for (int i = 0; i < LENGTH; i++) {
            ref.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return ref.toString();
    }
}
