package io.github.pongsapat3791.ufpmanager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * One login code at a time: /ufpmanager shows it and the page trades it for the token, once. A log can be pasted into a
 * forum, so what the command prints stops working after that, and after a few wrong tries.
 */
final class LoginCodes {
    static final int LIFETIME_MINUTES = 10;
    private static final int MAX_WRONG = 10;
    private static final String LETTERS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final SecureRandom random = new SecureRandom();
    private String code;
    private long expires;
    private int wrong;

    synchronized String next() {
        StringBuilder letters = new StringBuilder();
        for (int i = 0; i < 8; i++) letters.append(LETTERS.charAt(random.nextInt(LETTERS.length())));
        code = letters.toString();
        expires = System.currentTimeMillis() + LIFETIME_MINUTES * 60_000L;
        wrong = 0;
        return code;
    }

    synchronized boolean use(String given) {
        if (code == null || System.currentTimeMillis() > expires) {
            code = null;
            return false;
        }
        byte[] typed = given.trim().toUpperCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8), typed)) {
            code = null;
            return true;
        }
        if (++wrong >= MAX_WRONG) code = null;
        return false;
    }
}
