package com.example.seatreservation.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(error(exception.code(), exception.getMessage(), request));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> handleInvalidRequest(Exception exception, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error("INVALID_REQUEST", "Request validation failed", request));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException exception,
                                                        HttpServletRequest request) {
        if (exception.getHeaderName().equalsIgnoreCase("Authorization")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(error("UNAUTHORIZED", "A bearer token is required", request));
        }
        if (exception.getHeaderName().equalsIgnoreCase("Idempotency-Key")) {
            return ResponseEntity.badRequest()
                    .body(error("IDEMPOTENCY_KEY_REQUIRED", "An Idempotency-Key header is required", request));
        }
        return ResponseEntity.badRequest().body(error("INVALID_REQUEST", "A required header is missing", request));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> handleDatabaseUnavailable(DataAccessException exception, HttpServletRequest request) {
        LOGGER.error("Database operation failed request_id={}", requestId(request), exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error("DATABASE_UNAVAILABLE", "The database is temporarily unavailable", request));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error("NOT_FOUND", "The requested resource was not found", request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unexpected request failure request_id={}", requestId(request), exception);
        return ResponseEntity.internalServerError()
                .body(error("INTERNAL_ERROR", "An unexpected error occurred", request));
    }

    private ApiError error(String code, String message, HttpServletRequest request) {
        return new ApiError(new ErrorBody(code, message), requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        return requestId == null ? "unknown" : requestId.toString();
    }

    public record ApiError(ErrorBody error, @com.fasterxml.jackson.annotation.JsonProperty("request_id") String requestId) {
    }

    public record ErrorBody(String code, String message) {
    }
}