package com.aireader.api;

import com.aireader.application.AnalyzeTextUseCase;
import com.aireader.application.GenerateIllustrationUseCase;
import com.aireader.domain.AnalysisResult;
import com.aireader.infrastructure.web.ApiRequestLoggingFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AnalysisController.class, IllustrationController.class})
@ExtendWith(OutputCaptureExtension.class)
class ApiRequestErrorsTest {
    private static final String ANALYSES = "/api/v1/analyses";
    private static final String ILLUSTRATIONS = "/api/v1/illustrations";

    @Autowired WebApplicationContext context;
    @Autowired ApiRequestLoggingFilter requestLogging;
    @MockitoBean AnalyzeTextUseCase analyses;
    @MockitoBean GenerateIllustrationUseCase illustrations;

    private MockMvc mvc;

    @BeforeEach
    void setupMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(requestLogging).build();
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void reportsJsonReason(String body, String reason, boolean hasLocation) throws Exception {
        var result = mvc.perform(post(ANALYSES).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_JSON"))
                .andExpect(jsonPath("$.reason").value(reason));
        if (hasLocation) {
            result.andExpect(jsonPath("$.line").value(greaterThan(0)))
                    .andExpect(jsonPath("$.column").value(greaterThan(0)));
        }
        verifyNoInteractions(analyses);
    }

    static Stream<Arguments> invalidBodies() {
        return Stream.of(
                Arguments.of("", "BODY_REQUIRED", false),
                Arguments.of("{\n  selectedText: \"castle\"\n}", "EXPECTED_FIELD_NAME", true),
                Arguments.of("{\"selectedText\":\"castle\",}", "EXPECTED_FIELD_NAME", true),
                Arguments.of("{\"selectedText\" \"castle\"}", "EXPECTED_COLON", true),
                Arguments.of("{\"selectedText\":\"old \"castle\"\"}", "EXPECTED_COMMA", true),
                Arguments.of("{\"selectedText\":\"bad \\q escape\"}", "INVALID_ESCAPE", true),
                Arguments.of("{\"selectedText\":\"castle\"", "INCOMPLETE_JSON", true),
                Arguments.of("{} {}", "TRAILING_CONTENT", true),
                Arguments.of("[]", "INVALID_TYPE", true));
    }

    @Test
    void acceptsIndentedJsonWithEscapedQuotesAndLineBreaks() throws Exception {
        when(analyses.analyze(any(), any())).thenReturn(new AnalysisResult("Замок", "Описание"));
        String body = """
                {
                    "selectedText": "The \\"castle\\"",
                    "context": "First line.\\nSecond line."
                }
                """;
        mvc.perform(post(ANALYSES).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.translation").value("Замок"));
    }

    @Test
    void distinguishesUnknownFieldsAndInvalidIllustrationChoices() throws Exception {
        mvc.perform(post(ANALYSES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customField\":\"private text\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("UNKNOWN_FIELD"))
                .andExpect(jsonPath("$.allowedFields").value(hasItem("selectedText")));
        mvc.perform(post(ILLUSTRATIONS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedText\":\"castle\",\"context\":\"old castle\",\"style\":\"oil\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reason").value("INVALID_CHOICE"))
                .andExpect(jsonPath("$.field").value("style")).andExpect(jsonPath("$.allowedValues").value(hasItem("BOOK")));
        verifyNoInteractions(analyses, illustrations);
    }

    @Test
    void identifiesMissingAndOversizedFieldsAndMalformedPathId() throws Exception {
        mvc.perform(post(ANALYSES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"context\":\"" + "x".repeat(12_001) + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'selectedText')].code").value(hasItem("REQUIRED")))
                .andExpect(jsonPath("$.errors[?(@.field == 'context')].code").value(hasItem("INVALID_LENGTH")));
        mvc.perform(get(ILLUSTRATIONS + "/bad-id"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
                .andExpect(jsonPath("$.field").value("id"));
        verifyNoInteractions(analyses, illustrations);
    }

    @Test
    void returnsAndLogsTheSameServerRequestIdWithoutSubmittedTextOrCredentials(CapturedOutput output) throws Exception {
        var result = mvc.perform(post(ANALYSES).queryParam("debug", "private-query")
                        .header("X-Request-Id", "untrusted-client-id")
                        .header("X-Firebase-AppCheck", "private-app-check")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer private-authorization")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\n  \"selectedText\":\"private-book-text\nsecond line\"\n}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("UNESCAPED_CONTROL_CHARACTER"))
                .andExpect(jsonPath("$.detail").value(containsString("\\n"))).andReturn();

        String id = result.getResponse().getHeader("X-Request-Id");
        assertThat(id).isEqualTo(UUID.fromString(id).toString());
        jsonPath("$.requestId").value(id).match(result);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-book-text");
        assertThat(output.getAll()).contains("requestId=" + id, "status=400", "reason=UNESCAPED_CONTROL_CHARACTER")
                .doesNotContain("private-book-text", "private-app-check", "private-authorization", "private-query", "untrusted-client-id");
    }
}
