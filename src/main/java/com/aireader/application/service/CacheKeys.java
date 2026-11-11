package com.aireader.application.service;

import com.aireader.domain.TextContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;

/** Length prefixes avoid ambiguous field boundaries; preserve meaningful interior whitespace. */
public final class CacheKeys {
    private CacheKeys() { }

    public static String forContext(String scope, TextContext context, String... settings) {
        var fields = new java.util.ArrayList<String>();
        fields.add(scope);
        fields.add(context.book());
        fields.add(context.author());
        fields.add(context.edition());
        fields.add(context.selectedText());
        fields.add(context.context());
        fields.addAll(java.util.List.of(settings));
        return hash(fields.toArray(String[]::new));
    }

    public static String hash(String... fields) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                var bytes = Normalizer.normalize(field.replace("\r\n", "\n").strip(),
                        Normalizer.Form.NFC).getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
