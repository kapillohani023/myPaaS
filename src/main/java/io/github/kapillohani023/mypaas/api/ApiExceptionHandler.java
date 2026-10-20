package io.github.kapillohani023.mypaas.api;

import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.core.RateLimitedException;
import io.github.kapillohani023.mypaas.core.StepUpRequiredException;
import io.github.kapillohani023.mypaas.core.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;

@RestControllerAdvice(basePackages = "io.github.kapillohani023.mypaas.api")
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ErrorBody(String code, String message, List<String> violations,
                            String action, String resourceType, Long resourceId) {
    }

    public record ErrorResponse(ErrorBody error) {
    }

    @ExceptionHandler(StepUpRequiredException.class)
    public ResponseEntity<ErrorResponse> stepUp(StepUpRequiredException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(new ErrorBody(
                exception.code(), exception.getMessage(), List.of(),
                exception.action().name(), exception.resource().type().name(), exception.resource().id())));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> validation(ValidationException exception) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(new ErrorResponse(
                new ErrorBody(exception.code(), exception.getMessage(), exception.violations(), null, null, null)));
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ErrorResponse> rateLimited(RateLimitedException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(exception.retryAfterSeconds()))
                .body(new ErrorResponse(new ErrorBody(exception.code(), exception.getMessage(),
                        List.of(), null, null, null)));
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> domain(DomainException exception) {
        return ResponseEntity.status(exception.status()).body(new ErrorResponse(new ErrorBody(
                exception.code(), exception.getMessage(), List.of(), null, null, null)));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> accessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new ErrorResponse(
                new ErrorBody("forbidden", "Forbidden", List.of(), null, null, null)));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> badRequest(Exception exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(
                new ErrorBody("bad_request", "The request could not be parsed", List.of(), null, null, null)));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> tooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(new ErrorResponse(
                new ErrorBody("file_too_large", "Upload exceeds the configured limit",
                        List.of(), null, null, null)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unexpected(Exception exception) {
        log.error("Unhandled API failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
                new ErrorBody("internal_error", "An unexpected error occurred",
                        List.of(), null, null, null)));
    }
}
