package edu.northwestu.project1p2.controllers;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorDescriptionTests {

    @ParameterizedTest
    @CsvSource({
        "400, Bad request",
        "401, Sign in required",
        "403, Access denied",
        "404, Page not found",
        "405, Method not allowed",
        "429, Too many requests",
        "500, Something went wrong",
        "503, Service unavailable",
        // Unlisted codes fall back by class.
        "418, Request problem",
        "451, Request problem",
        "599, Something went wrong",
    })
    void eachStatusHasAPlainLanguageTitle(final int status, final String title) {
        final ErrorDescription description = ErrorDescription.forStatus(status);

        assertThat(description.title()).isEqualTo(title);
        assertThat(description.description()).isNotBlank();
    }
}
