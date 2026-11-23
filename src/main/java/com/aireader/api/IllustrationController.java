package com.aireader.api;

import com.aireader.api.dto.*;
import com.aireader.application.GenerateIllustrationUseCase;
import com.aireader.application.port.IllustrationException;
import com.aireader.domain.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/illustrations")
@Tag(name = "Иллюстрации", description = "Одна иллюстрация по выделению, кэш на 48 часов")
public class IllustrationController {
    private final GenerateIllustrationUseCase illustrations;
    private final boolean enabled;
    public IllustrationController(GenerateIllustrationUseCase illustrations, @Value("${illustration.enabled}") boolean enabled) {
        this.illustrations = illustrations;
        this.enabled = enabled;
    }
    @PostMapping
    @Operation(summary = "Создать иллюстрацию или получить существующее задание",
            description = "202 — опрашивайте Location; 200 — готовый или ошибочный результат. "
                    + "Используйте ту же гостевую сессию при опросе статуса и загрузке файла. "
                    + "HTTP 400 возвращает INVALID_JSON с причиной и позицией или VALIDATION_FAILED со списком полей.")
    ResponseEntity<IllustrationResponse> generate(@Valid @RequestBody IllustrationRequest request,
            @AuthenticationPrincipal String scope) {
        if (!enabled) throw new IllustrationException("ILLUSTRATION_DISABLED", 503);
        var job = illustrations.generate(new TextContext(request.book(), request.author(), request.edition(),
                request.selectedText(), request.context()), new IllustrationOptions(request.style(), request.size()), scope);
        return response(job);
    }
    @GetMapping("/{id}")
    @Operation(summary = "Получить статус генерации")
    ResponseEntity<IllustrationResponse> status(@PathVariable UUID id, @AuthenticationPrincipal String scope) {
        return response(illustrations.get(id, scope));
    }
    @GetMapping("/{id}/image")
    @Operation(summary = "Получить готовое изображение")
    ResponseEntity<byte[]> image(@PathVariable UUID id, @AuthenticationPrincipal String scope) {
        var image = illustrations.image(id, scope);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.contentType()))
                .header("Content-Disposition", "inline; filename=\"illustration-" + id
                        + ("image/png".equals(image.contentType()) ? ".png\"" : ".jpg\""))
                .header("X-Content-Type-Options", "nosniff").body(image.bytes());
    }
    private static ResponseEntity<IllustrationResponse> response(Illustration job) {
        boolean pending = job.status() == Illustration.Status.QUEUED || job.status() == Illustration.Status.GENERATING;
        var builder = ResponseEntity.status(pending ? 202 : 200)
                .location(URI.create("/api/v1/illustrations/" + job.id()));
        if (pending) builder.header("Retry-After", "2");
        return builder.body(IllustrationResponse.from(job));
    }
}
