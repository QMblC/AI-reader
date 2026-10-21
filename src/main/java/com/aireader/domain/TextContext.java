package com.aireader.domain;

import java.util.Objects;

public record TextContext(String book, String author, String edition,
                          String selectedText, String context) {
    public TextContext {
        book = Objects.requireNonNullElse(book, "").strip();
        author = Objects.requireNonNullElse(author, "").strip();
        edition = Objects.requireNonNullElse(edition, "").strip();
        Objects.requireNonNull(selectedText);
        Objects.requireNonNull(context);
        if (selectedText.isBlank() || context.isBlank()) {
            throw new IllegalArgumentException("Selected text and context must not be blank");
        }
    }
}
