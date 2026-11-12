package com.dettavo.common.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * The te_session cookie: HttpOnly, SameSite=Strict, Path=/ and not Secure (plain http on
 * loopback). 127.0.0.1:2061 and 127.0.0.1:2060 are the same site, so the browser sends it
 * on the client's credentialed requests.
 */
@Service
@RequiredArgsConstructor
public class SessionCookieService {

    public static final String COOKIE_NAME = "te_session";

    /** Kept as long as Chrome allows, so a paired browser stays paired. */
    private static final Duration MAX_AGE = Duration.ofDays(400);

    private final PairingService pairingService;

    public ResponseCookie createSessionCookie() {
        return ResponseCookie.from(COOKIE_NAME, pairingService.sessionValue())
                .httpOnly(true)
                .secure(false)
                .sameSite("Strict")
                .path("/")
                .maxAge(MAX_AGE)
                .build();
    }

    public boolean isPaired(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return false;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName()) && pairingService.isValidSession(cookie.getValue())) {
                return true;
            }
        }
        return false;
    }
}
