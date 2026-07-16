package sh.repost.client.internal;

import java.security.SecureRandom;
import java.util.Objects;

final class Cuid2 {
    private static final char[] LEADING = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final char[] BODY = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int LENGTH = 24;

    private Cuid2() { }

    static String next(SecureRandom random) {
        Objects.requireNonNull(random, "random");
        char[] value = new char[LENGTH];
        value[0] = LEADING[random.nextInt(LEADING.length)];
        for (int index = 1; index < value.length; index++) {
            value[index] = BODY[random.nextInt(BODY.length)];
        }
        return new String(value);
    }
}
