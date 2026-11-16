package com.flashticket.infrastructure.security;

import java.util.regex.Pattern;

/**
 * TÓPICO (Fase 5): Prevenção contra XSS (Cross-Site Scripting).
 *
 * Remove tags de execução de script (<script>, <iframe>, <object>, <embed>),
 * atributos de eventos inline (onerror=, onload=, onclick=) e protocolos
 * javascript:/vbscript:, além de escapar tags HTML remanescentes.
 */
public final class XssSanitizer {

    private static final Pattern SCRIPT_TAG_PATTERN = Pattern.compile(
            "(?i)<script[^>]*>[\\s\\S]*?</script>|<script[^>]*/>|<script[^>]*>");

    private static final Pattern DANGEROUS_TAGS_PATTERN = Pattern.compile(
            "(?i)</?(iframe|object|embed|svg|math|form|input|button|style|link|meta)[^>]*>");

    private static final Pattern EVENT_HANDLER_ATTR_PATTERN = Pattern.compile(
            "(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)");

    private static final Pattern JAVASCRIPT_PROTOCOL_PATTERN = Pattern.compile(
            "(?i)(javascript|vbscript|data)\\s*:");

    private XssSanitizer() {
    }

    public static String sanitize(String rawInput) {
        if (rawInput == null || rawInput.isBlank()) {
            return rawInput;
        }

        String cleaned = SCRIPT_TAG_PATTERN.matcher(rawInput).replaceAll("");
        cleaned = DANGEROUS_TAGS_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = EVENT_HANDLER_ATTR_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = JAVASCRIPT_PROTOCOL_PATTERN.matcher(cleaned).replaceAll("blocked:");

        // Escapa caracteres especiais de abertura/fechamento de tags HTML (< e >)
        cleaned = cleaned
                .replace("<", "&lt;")
                .replace(">", "&gt;");

        return cleaned.trim();
    }
}
