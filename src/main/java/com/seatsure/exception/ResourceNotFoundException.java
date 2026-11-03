package com.seatsure.exception;

/** 404: the thing asked for doesn't exist, or isn't the caller's to see. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    /** "Show not found: 7". */
    public static ResourceNotFoundException of(String what, Object id) {
        return new ResourceNotFoundException(what + " not found: " + id);
    }
}
