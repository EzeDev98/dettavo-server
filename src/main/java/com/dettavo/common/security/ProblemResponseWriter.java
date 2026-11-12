package com.dettavo.common.security;

import com.dettavo.common.exception.ErrorCode;
import com.dettavo.config.ApplicationProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Writes a ProblemDetail body from a servlet filter, where GlobalExceptionHandler cannot reach.
 * When the request comes from an allowed origin, the CORS headers are added too, so the
 * client can read the status (for example 401 to show the "Not paired" screen) instead of
 * seeing a network error.
 */
@Component
public class ProblemResponseWriter {

    private final List<String> allowedOrigins;

    public ProblemResponseWriter(ApplicationProperties properties) {
        this.allowedOrigins = List.copyOf(properties.security().allowedOrigins());
    }

    public void write(HttpServletRequest request, HttpServletResponse response,
                      HttpStatus status, ErrorCode code, String detail) throws IOException {
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        if (origin != null && allowedOrigins.contains(origin)) {
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
            response.setHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
            response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
        }

        String body = "{\"type\":\"about:blank\""
                + ",\"title\":" + quote(status.getReasonPhrase())
                + ",\"status\":" + status.value()
                + ",\"detail\":" + quote(detail)
                + ",\"instance\":" + quote(request.getRequestURI())
                + ",\"code\":" + quote(code.name())
                + "}";

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body);
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (character < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) character));
                    } else {
                        quoted.append(character);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
