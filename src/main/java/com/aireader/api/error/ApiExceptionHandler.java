package com.aireader.api.error;

import com.aireader.application.port.LlmUnavailableException;
import com.aireader.application.port.InvalidLlmResponseException;
import com.aireader.application.port.IllustrationException;
import com.aireader.application.port.RequestQuotaExceededException;
import com.aireader.infrastructure.web.ApiRequestLoggingFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return handleExceptionInternal(ex, JsonRequestProblem.from(ex.getCause()), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        boolean illustrationId = ex instanceof MethodArgumentTypeMismatchException parameter
                && UUID.class.equals(parameter.getRequiredType()) && "id".equals(parameter.getName());
        var problem = ProblemDetail.forStatusAndDetail(status, illustrationId
                ? "Передайте UUID иллюстрации в параметре id."
                : "Проверьте тип параметра запроса.");
        problem.setProperty("code", "INVALID_PARAMETER");
        if (illustrationId) {
            problem.setProperty("field", "id");
        }
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, "Исправьте значения полей из списка errors.");
        problem.setProperty("code", "VALIDATION_FAILED");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> {
                    String code = switch (error.getCode()) {
                        case "NotBlank" -> "REQUIRED";
                        case "Size" -> "INVALID_LENGTH";
                        case null, default -> "INVALID_VALUE";
                    };
                    return Map.of("field", error.getField(), "code", code, "message",
                            error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage());
                })
                .toList();
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem;
        if (body instanceof ProblemDetail detail) {
            problem = detail;
        } else {
            var resolved = HttpStatus.resolve(status.value());
            problem = ProblemDetail.forStatusAndDetail(status,
                    resolved == null ? "Request failed" : resolved.getReasonPhrase());
        }
        return super.handleExceptionInternal(ex, ApiRequestLoggingFilter.recordProblem(request, problem), headers, status, request);
    }

    @ExceptionHandler(InvalidLlmResponseException.class)
    ProblemDetail invalidLlmResponse(InvalidLlmResponseException ex, WebRequest request) {
        LOG.warn("LLM response validation failed");
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "Модель вернула некорректный ответ. Ожидается JSON с непустыми строками translation и explanation.");
        problem.setProperty("code", "INVALID_LLM_RESPONSE");
        return ApiRequestLoggingFilter.recordProblem(request, problem);
    }

    @ExceptionHandler(LlmUnavailableException.class)
    ProblemDetail llmUnavailable(LlmUnavailableException ex, WebRequest request) {
        String code = switch (ex.providerStatus() == null ? 0 : ex.providerStatus()) {
            case 400 -> "LLM_PROVIDER_BAD_REQUEST";
            case 401 -> "LLM_AUTHENTICATION_FAILED";
            case 403 -> "LLM_ACCESS_DENIED";
            case 404 -> "LLM_PROVIDER_NOT_FOUND";
            case 429 -> "LLM_PROVIDER_RATE_LIMIT";
            default -> "LLM_PROVIDER_UNAVAILABLE";
        };
        LOG.warn("LLM request failed: code={}, providerStatus={}", code, ex.providerStatus());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Analysis provider is temporarily unavailable");
        problem.setProperty("code", code);
        if (ex.providerStatus() != null) {
            problem.setProperty("providerStatus", ex.providerStatus());
        }
        return ApiRequestLoggingFilter.recordProblem(request, problem);
    }

    @ExceptionHandler(IllustrationException.class)
    ProblemDetail illustrationFailure(IllustrationException ex, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(ex.status()), ex.code());
        problem.setProperty("code", ex.code());
        return ApiRequestLoggingFilter.recordProblem(request, problem);
    }

    @ExceptionHandler(RequestQuotaExceededException.class)
    ResponseEntity<ProblemDetail> quotaExceeded(RequestQuotaExceededException ex, WebRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Request quota exceeded");
        problem.setProperty("code", ex.code());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
                .body(ApiRequestLoggingFilter.recordProblem(request, problem));
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex, WebRequest request) {
        LOG.error("Unexpected request failure: {}", ex.getClass().getName());
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        return ApiRequestLoggingFilter.recordProblem(request, problem);
    }
}
