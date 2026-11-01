package com.seatsure.exception;

import java.util.List;

/** Some of the requested seats were taken first (HTTP 409); the response lists them so the UI can grey them out. */
public class SeatsUnavailableException extends ConflictException {

    private final List<Long> seatIds;

    public SeatsUnavailableException(List<Long> seatIds) {
        super(seatIds.size() == 1 ? "That seat was just taken. Please pick another."
                : seatIds.size() + " of your seats were just taken. Please pick others.");
        this.seatIds = List.copyOf(seatIds);
    }

    public List<Long> getSeatIds() {
        return seatIds;
    }
}
