package com.aireader.domain;

public record IllustrationOptions(Style style, Size size) {
    public IllustrationOptions {
        style = style == null ? Style.BOOK : style;
        size = size == null ? Size.SQUARE : size;
    }
    public enum Style { BOOK, WATERCOLOR, REALISTIC, COMIC }
    public enum Size {
        SQUARE("1024x1024"), PORTRAIT("1024x1536"), LANDSCAPE("1536x1024");
        private final String apiValue;
        Size(String apiValue) { this.apiValue = apiValue; }
        public String apiValue() { return apiValue; }
    }
}
