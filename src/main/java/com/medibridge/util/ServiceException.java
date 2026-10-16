package com.medibridge.util;

/** A problem that should be reported to the client with a specific HTTP status. */
public class ServiceException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;

    public ServiceException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
