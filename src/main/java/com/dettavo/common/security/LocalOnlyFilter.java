package com.dettavo.common.security;

import com.dettavo.common.exception.ErrorCode;
import com.dettavo.config.ApplicationProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps the local server out of reach of other web pages the user visits. There is no pairing:
 * the server listens on loopback only, and these checks stand in its place.
 * <ul>
 *   <li>the Host header must name an allowed host with the server port (blocks DNS rebinding);</li>
 *   <li>POST, PUT, PATCH and DELETE must come from an allowed origin (blocks cross-site writes);</li>
 *   <li>OPTIONS preflights pass after the Host check, so CORS in WebConfig can answer them, and
 *       CORS keeps every other site from reading a response;</li>
 *   <li>every response carries nosniff and no-referrer.</li>
 * </ul>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalOnlyFilter extends OncePerRequestFilter {

    private static final Set<String> WRITE_METHODS = Set.of(
            HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.PATCH.name(), HttpMethod.DELETE.name());

    private final Set<String> allowedHostHeaders;
    private final List<String> allowedOrigins;
    private final ProblemResponseWriter problemResponseWriter;

    public LocalOnlyFilter(ApplicationProperties properties,
                           @Value("${server.port}") int serverPort,
                           ProblemResponseWriter problemResponseWriter) {
        this.allowedHostHeaders = properties.security().allowedHosts().stream()
                .map(host -> host.toLowerCase(Locale.ROOT) + ":" + serverPort)
                .collect(Collectors.toUnmodifiableSet());
        this.allowedOrigins = List.copyOf(properties.security().allowedOrigins());
        this.problemResponseWriter = problemResponseWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");

        String host = request.getHeader(HttpHeaders.HOST);
        if (host == null || !allowedHostHeaders.contains(host.toLowerCase(Locale.ROOT))) {
            log.warn("Refused request with Host {} to {} {}", host, request.getMethod(), request.getRequestURI());
            problemResponseWriter.write(request, response, HttpStatus.FORBIDDEN, ErrorCode.HOST_NOT_ALLOWED,
                    "Host " + host + " is not allowed");
            return;
        }

        if (WRITE_METHODS.contains(request.getMethod())) {
            String origin = request.getHeader(HttpHeaders.ORIGIN);
            if (origin == null || !allowedOrigins.contains(origin)) {
                log.warn("Refused {} {} from origin {}", request.getMethod(), request.getRequestURI(), origin);
                problemResponseWriter.write(request, response, HttpStatus.FORBIDDEN, ErrorCode.ORIGIN_NOT_ALLOWED,
                        "Origin " + origin + " is not allowed");
                return;
            }
        }

        chain.doFilter(request, response);
    }
}
