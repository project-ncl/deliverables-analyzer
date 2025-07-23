package org.jboss.pnc.deliverablesanalyzer.rest.exception;

/**
 * Exception thrown specifically on respond codes 404 and 400.
 */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
