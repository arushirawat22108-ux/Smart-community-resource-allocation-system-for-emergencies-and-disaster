package com.cras.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * SHA-256 hash chaining for the audit log (tamper-evident, like a tiny blockchain).
 * entryHash = SHA-256( prevHash | actor | action | detail | createdAt ).
 * Every field is length-prefixed, so "ab"+"c" can never collide with "a"+"bc".
 * Pure logic: no Spring, no database.
 */
public final class AuditHasher {

    public static final String GENESIS = "GENESIS";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");

    private AuditHasher() {}

    public static String hash(String prevHash, String actor, String action, String detail, LocalDateTime createdAt) {
        String ts = createdAt == null ? null : createdAt.truncatedTo(ChronoUnit.MILLIS).format(TS);
        String canonical = part(prevHash) + part(actor) + part(action) + part(detail) + part(ts);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String part(String s) {
        return s == null ? "-1:;" : s.length() + ":" + s + ";";
    }
}
