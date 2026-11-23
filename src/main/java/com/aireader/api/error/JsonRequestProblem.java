package com.aireader.api.error;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.core.io.JsonEOFException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.Arrays;
import java.util.Set;

final class JsonRequestProblem {
    private static final Set<String> REQUEST_FIELDS = Set.of("book", "author", "edition", "selectedText", "context", "style", "size");

    private JsonRequestProblem() { }

    static ProblemDetail from(Throwable cause) {
        if (cause instanceof JsonMappingException mapping && mapping.getCause() instanceof JsonProcessingException nested) {
            var problem = from(nested);
            addField(problem, mapping);
            return problem;
        }
        return switch (cause) {
            case UnrecognizedPropertyException ex -> {
                var problem = problem("UNKNOWN_FIELD", "JSON содержит неизвестное поле. Проверьте название по списку допустимых полей.", ex.getLocation());
                problem.setProperty("allowedFields", ex.getKnownPropertyIds().stream().map(Object::toString).sorted().toList());
                yield problem;
            }
            case InvalidFormatException ex when ex.getTargetType() != null && ex.getTargetType().isEnum() -> {
                var problem = forField(ex, "INVALID_CHOICE", "Передайте одно из допустимых строковых значений с учётом регистра.");
                problem.setProperty("allowedValues", Arrays.stream(ex.getTargetType().getEnumConstants())
                        .map(value -> ((Enum<?>) value).name()).toList());
                yield problem;
            }
            case StreamConstraintsException ex -> problem("JSON_LIMIT_EXCEEDED", "JSON превышает допустимую длину или глубину вложенности.", ex.getLocation());
            case JsonEOFException ex -> problem("INCOMPLETE_JSON", "JSON неожиданно закончился. Проверьте закрывающую кавычку и скобки.", ex.getLocation());
            case JsonParseException ex -> syntax(ex);
            case MismatchedInputException ex -> {
                if (ex.getOriginalMessage().startsWith("Trailing token")) {
                    yield problem("TRAILING_CONTENT", "Оставьте только один JSON-объект и проверьте символы после закрывающей скобки.", ex.getLocation());
                }
                if (ex.getOriginalMessage().startsWith("No content to map")) {
                    yield problem("BODY_REQUIRED", "Передайте тело запроса в виде JSON-объекта.", ex.getLocation());
                }
                yield forField(ex, "INVALID_TYPE", ex.getPath().isEmpty()
                        ? "На верхнем уровне ожидается JSON-объект в фигурных скобках."
                        : "Проверьте тип значения в указанном поле. Для текста нужны двойные кавычки.");
            }
            case null -> problem("BODY_REQUIRED", "Передайте тело запроса в виде JSON-объекта.", null);
            default -> problem("MALFORMED_JSON", "Проверьте формат тела JSON и его кодировку UTF-8.", null);
        };
    }

    private static ProblemDetail syntax(JsonParseException ex) {
        // Treat parser diagnostics as input. Match known phrases and return only fixed guidance.
        String message = ex.getOriginalMessage();
        var location = ex.getLocation();
        if (message.contains("expecting double-quote to start field name")) {
            return problem("EXPECTED_FIELD_NAME", "Заключите имя поля в двойные кавычки. Проверьте, нет ли лишней запятой перед закрывающей скобкой.", location);
        }
        if (message.contains("expecting comma to separate")) {
            return problem("EXPECTED_COMMA", "Между полями или элементами нужна запятая. Если ошибка внутри текста, экранируйте внутренние двойные кавычки как \\\".", location);
        }
        if (message.contains("expecting a colon to separate")) {
            return problem("EXPECTED_COLON", "Поставьте двоеточие между именем поля и значением.", location);
        }
        if (message.contains("has to be escaped") || message.contains("Illegal unquoted character")) {
            return problem("UNESCAPED_CONTROL_CHARACTER", "Внутри строки замените переносы строк на \\n, а табуляцию на \\t.", location);
        }
        if (message.contains("Unrecognized character escape") || message.contains("hex-digit for character escape")) {
            return problem("INVALID_ESCAPE", "Проверьте экранирование после обратной косой черты. Для самой косой черты используйте \\\\.", location);
        }
        return problem("MALFORMED_JSON", "Проверьте парность скобок и двойных кавычек, запятые и экранирование строк.", location);
    }

    private static ProblemDetail forField(MismatchedInputException ex, String reason, String detail) {
        var problem = problem(reason, detail, ex.getLocation());
        addField(problem, ex);
        return problem;
    }

    private static void addField(ProblemDetail problem, JsonMappingException ex) {
        if (!ex.getPath().isEmpty()) {
            String field = ex.getPath().getFirst().getFieldName();
            if (field != null && REQUEST_FIELDS.contains(field)) {
                problem.setProperty("field", field);
            }
        }
    }

    private static ProblemDetail problem(String reason, String detail, JsonLocation location) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("code", "INVALID_JSON");
        problem.setProperty("reason", reason);
        if (location != null && location.getLineNr() > 0) {
            problem.setProperty("line", location.getLineNr());
        }
        if (location != null && location.getColumnNr() > 0) {
            problem.setProperty("column", location.getColumnNr());
        }
        return problem;
    }
}
