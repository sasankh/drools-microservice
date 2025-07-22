package com.company.drools.api.exception;

/**
 * Exception thrown when operations exceed their configured timeout
 */
public class TimeoutException extends RuntimeException {
    
    private final String operation;
    private final long timeoutSeconds;
    
    public TimeoutException(String operation, long timeoutSeconds) {
        super(String.format("Operation '%s' timed out after %d seconds", operation, timeoutSeconds));
        this.operation = operation;
        this.timeoutSeconds = timeoutSeconds;
    }
    
    public TimeoutException(String operation, long timeoutSeconds, Throwable cause) {
        super(String.format("Operation '%s' timed out after %d seconds", operation, timeoutSeconds), cause);
        this.operation = operation;
        this.timeoutSeconds = timeoutSeconds;
    }
    
    public String getOperation() {
        return operation;
    }
    
    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }
}