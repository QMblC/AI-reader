package com.aireader.api;

import com.aireader.api.dto.AnalysisRequest;
import com.aireader.api.dto.AnalysisResponse;
import com.aireader.application.AnalyzeTextUseCase;
import com.aireader.domain.TextContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analyses")
@Tag(name = "Анализ текста", description = "Контекстный перевод и объяснение выделенной фразы")
public class AnalysisController {
    private final AnalyzeTextUseCase analyzeText;

    public AnalysisController(AnalyzeTextUseCase analyzeText) {
        this.analyzeText = analyzeText;
    }

    @PostMapping
    @Operation(summary = "Проанализировать выделенную фразу",
            description = "Принимает фразу, окружающий контекст и необязательные сведения о книге. "
                    + "Перевод и объяснение — на русском. Пока LLM не подключена, любой корректный запрос "
                    + "возвращает фиксированный пример про 'kick the bucket'. Лимит по умолчанию — "
                    + "60 POST-запросов в минуту на экземпляр приложения, общий для всех клиентов.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AnalysisRequest.class),
                            examples = @ExampleObject(name = "Идиома", value = """
                                    {
                                      "book": "",
                                      "author": "",
                                      "edition": "",
                                      "selectedText": "He kicked the bucket",
                                      "context": "After a long illness, he finally kicked the bucket."
                                    }
                                    """))))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Анализ выполнен; сейчас ответ-заглушка",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AnalysisResponse.class))),
            @ApiResponse(responseCode = "400", description = "Некорректный JSON или ошибка валидации",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "415", description = "Требуется тело запроса в формате JSON",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "429", description = "Превышен общий лимит запросов анализа",
                    headers = @Header(name = "Retry-After", description = "Секунды до следующей попытки",
                            schema = @Schema(type = "integer", minimum = "1")),
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "500", description = "Внутренняя ошибка",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "502", description = "Ответ модели не соответствует формату анализа",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "type": "about:blank",
                                      "title": "Bad Gateway",
                                      "status": 502,
                                      "detail": "Модель вернула некорректный ответ. Ожидается JSON с непустыми строками translation и explanation.",
                                      "code": "INVALID_LLM_RESPONSE"
                                    }
                                    """))),
            @ApiResponse(responseCode = "503", description = "LLM-провайдер временно недоступен",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    AnalysisResponse analyze(@Valid @RequestBody AnalysisRequest request) {
        var result = analyzeText.analyze(new TextContext(request.book(), request.author(), request.edition(),
                request.selectedText(), request.context()));
        return new AnalysisResponse(result.translation(), result.explanation());
    }
}
