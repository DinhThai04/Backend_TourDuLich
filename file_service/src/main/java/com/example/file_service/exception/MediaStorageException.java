package com.example.file_service.exception;

public class MediaStorageException extends RuntimeException {
    public MediaStorageException(String message, Throwable cause) { super(message, cause); }
    public MediaStorageException(String message) { super(message); }
}
