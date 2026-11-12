package com.dettavo.common.security;

import com.dettavo.common.file.WorkspacePaths;
import com.dettavo.config.ApplicationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * Owns the pairing key in ~/Dettavo/pairing.key (32 random bytes, base64url, readable by the
 * owner only). The session cookie value is HMAC-SHA256(key, "session-v1"), so a paired browser
 * stays paired across restarts; deleting the key file and restarting revokes every browser.
 */
@Slf4j
@Service
public class PairingService {

    private static final int KEY_BYTES = 32;
    private static final String SESSION_LABEL = "session-v1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Set<PosixFilePermission> OWNER_READ_WRITE = PosixFilePermissions.fromString("rw-------");

    private final Path keyFile;
    private final String pairingUrl;
    private final String pairingKey;
    private final String sessionValue;

    public PairingService(WorkspacePaths workspacePaths, ApplicationProperties properties) {
        this.keyFile = workspacePaths.pairingKeyFile();
        this.pairingKey = loadOrCreateKey(keyFile);
        this.sessionValue = hmac(pairingKey, SESSION_LABEL);
        this.pairingUrl = properties.security().allowedOrigins().getFirst() + "/pair#" + pairingKey;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logPairingKey() {
        log.info("Pairing key is in {}. To pair a browser, open {}", keyFile, pairingUrl);
    }

    /** Compares a key sent by the pair page with the stored key in constant time. */
    public boolean matchesKey(String candidate) {
        return candidate != null && constantTimeEquals(candidate, pairingKey);
    }

    /** The value the session cookie must hold. */
    public String sessionValue() {
        return sessionValue;
    }

    public boolean isValidSession(String cookieValue) {
        return cookieValue != null && constantTimeEquals(cookieValue, sessionValue);
    }

    private static String loadOrCreateKey(Path keyFile) {
        try {
            if (Files.exists(keyFile)) {
                String existing = Files.readString(keyFile, StandardCharsets.US_ASCII).trim();
                if (!existing.isEmpty()) {
                    Files.setPosixFilePermissions(keyFile, OWNER_READ_WRITE);
                    return existing;
                }
                Files.delete(keyFile);
            }

            byte[] random = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(random);
            String key = Base64.getUrlEncoder().withoutPadding().encodeToString(random);

            Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(OWNER_READ_WRITE));
            Files.writeString(keyFile, key, StandardCharsets.US_ASCII);
            log.info("Created a new pairing key at {}", keyFile);
            return key;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read or create " + keyFile, e);
        }
    }

    private static String hmac(String key, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
