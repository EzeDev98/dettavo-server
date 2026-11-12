package com.dettavo.feature.session;

import com.dettavo.common.exception.PairingKeyInvalidException;
import com.dettavo.common.security.PairingService;
import com.dettavo.common.security.SessionCookieService;
import com.dettavo.config.ApiPaths;
import com.dettavo.feature.session.dto.PairRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@Tag(name = "Session", description = "Pairing a browser with this server")
public class SessionController {

    private final PairingService pairingService;
    private final SessionCookieService sessionCookieService;

    @PostMapping(ApiPaths.SESSION_PAIR)
    @Operation(summary = "Pair this browser", description = "Exchanges the pairing key for the te_session cookie. 204 on success, 401 for a wrong key.")
    public ResponseEntity<Void> pair(@Valid @RequestBody PairRequest request) {
        if (!pairingService.matchesKey(request.key())) {
            log.warn("Pairing refused: wrong key");
            throw new PairingKeyInvalidException();
        }
        log.info("Browser paired");
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, sessionCookieService.createSessionCookie().toString())
                .build();
    }
}
