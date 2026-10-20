package io.github.kapillohani023.mypaas.dashboard;

import io.github.kapillohani023.mypaas.core.DomainException;
import io.github.kapillohani023.mypaas.core.StepUpRequiredException;
import io.github.kapillohani023.mypaas.core.ValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice(assignableTypes = DashboardActionController.class)
public class DashboardExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DashboardExceptionHandler.class);

    @ExceptionHandler(StepUpRequiredException.class)
    public ResponseEntity<Map<String, Object>> stepUp(StepUpRequiredException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error(exception.code(),
                "Additional verification is required", exception.action().name(),
                exception.resource().type().name(), exception.resource().id()));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> validation(ValidationException exception) {
        Map<String, Object> body = error(exception.code(), exception.getMessage(), null, null, null);
        body.put("violations", exception.violations());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String, Object>> domain(DomainException exception) {
        return ResponseEntity.status(exception.status()).body(error(exception.code(),
                exception.getMessage(), null, null, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception exception) {
        log.error("Dashboard action failed", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error("internal_error", "The action could not be completed", null, null, null));
    }

    private static Map<String, Object> error(String code, String message, String action,
                                             String resourceType, Long resourceId) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("action", action);
        error.put("resourceType", resourceType);
        error.put("resourceId", resourceId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return body;
    }
}
