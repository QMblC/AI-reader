package com.aireader.api;

import com.aireader.infrastructure.security.GuestSessions;
import com.aireader.infrastructure.web.ApiRequestLoggingFilter;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@RestController
@RequestMapping("/api/v1/guest-sessions")
class GuestSessionController {
    private final GuestSessions sessions;

    GuestSessionController(GuestSessions sessions) {
        this.sessions = sessions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Открыть гостевую сессию")
    GuestSessions.IssuedSession create(@RequestAttribute String verifiedAppId) {
        return sessions.create(verifiedAppId);
    }

    @PostMapping("/refresh")
    @Operation(summary = "Обновить токен доступа", description = "Для этого запроса передайте refreshToken как Bearer-токен.")
    ResponseEntity<?> refresh(@RequestAttribute String verifiedAppId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization, WebRequest request) {
        var access = sessions.refresh(verifiedAppId, authorization);
        if (access.isPresent()) {
            return ResponseEntity.ok(access.get());
        }
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "A valid guest refresh token is required");
        problem.setProperty("code", "GUEST_REFRESH_INVALID");
        return ResponseEntity.of(ApiRequestLoggingFilter.recordProblem(request, problem))
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").build();
    }
}
