package com.aireader.api.error;

import com.aireader.application.port.LlmUnavailableException;
import com.aireader.application.port.InvalidLlmResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, "Request validation failed");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "message",
                        error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        // Avoid exposing JSON parser internals or submitted text in HTTP errors.
        if (!(body instanceof ProblemDetail)) {
            var resolved = HttpStatus.resolve(status.value());
            body = ProblemDetail.forStatusAndDetail(status,
                    resolved == null ? "Request failed" : resolved.getReasonPhrase());
        }
        return super.handleExceptionInternal(ex, body, headers, status, request);
    }

    @ExceptionHandler(InvalidLlmResponseException.class)
    ProblemDetail invalidLlmResponse(InvalidLlmResponseException ex) {
        LOG.warn("LLM response validation failed");
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "Модель вернула некорректный ответ. Ожидается JSON с непустыми строками translation и explanation.");
        problem.setProperty("code", "INVALID_LLM_RESPONSE");
        return problem;
    }

    @ExceptionHandler(LlmUnavailableException.class)
    ProblemDetail llmUnavailable(LlmUnavailableException ex) {
        LOG.warn("LLM request failed: {}", ex.getClass().getSimpleName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Analysis provider is temporarily unavailable");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        LOG.error("Unexpected request failure: {}", ex.getClass().getName());
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred");
    }
}
