package cc.nunez.couchcurfew;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** The parent PIN: 4 to 8 digits, stored only as a salted PBKDF2 hash. Plain Java, tested by test.sh. */
final class Pin {
    private Pin() {}

    private static final int ITERATIONS = 20_000, BITS = 256;

    static boolean wellFormed(String pin) {
        return pin != null && pin.matches("\\d{4,8}");
    }

    static String newSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    static String hash(String pin, String salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), Base64.getDecoder().decode(salt), ITERATIONS, BITS);
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
            return Base64.getEncoder().encodeToString(key);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    static boolean matches(String pin, String salt, String expectedHash) {
        if (!wellFormed(pin) || salt == null || expectedHash == null) return false;
        return MessageDigest.isEqual(hash(pin, salt).getBytes(), expectedHash.getBytes());
    }

    /** How long the PIN screen waits after this many wrong tries in a row: free for 4, then one more minute each. */
    static long lockoutMs(int failures) {
        return failures < 5 ? 0 : (failures - 4) * 60_000L;
    }
}
