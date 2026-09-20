package com.pms.shared.exception;

/**
 * Thrown by services (via repository.findActiveById(id).orElseThrow(...)) when a row is
 * missing or soft-deleted; GlobalExceptionHandler turns it into a 404. Unchecked so it can
 * escape an orElseThrow lambda cleanly and still roll back the surrounding @Transactional.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
