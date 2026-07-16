package sh.repost.client.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class DefaultGeneratorsTest {
    private static final Pattern UUID_V4 = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern CUID2 = Pattern.compile("^[a-z][a-z0-9]{23}$");

    @Test
    void usesUtcClockAndSecureUuidCuidShapes() {
        Instant fixed = Instant.parse("2026-07-14T09:08:07.654321Z");
        DefaultGenerators generators = new DefaultGenerators(
                Clock.fixed(fixed, ZoneOffset.UTC), new SecureRandom());

        assertEquals(fixed, generators.now());
        String firstUuid = generators.uuid();
        String secondUuid = generators.uuid();
        assertTrue(UUID_V4.matcher(firstUuid).matches());
        assertTrue(UUID_V4.matcher(secondUuid).matches());
        assertNotEquals(firstUuid, secondUuid);
        String firstCuid = generators.cuid();
        String secondCuid = generators.cuid();
        assertTrue(CUID2.matcher(firstCuid).matches());
        assertTrue(CUID2.matcher(secondCuid).matches());
        assertNotEquals(firstCuid, secondCuid);
    }

    @Test
    void cuidShapeHasFixedLeadingLetterAndLength() {
        assertEquals(
                "a00000000000000000000000",
                Cuid2.next(new ZeroSecureRandom()));
    }

    private static final class ZeroSecureRandom extends SecureRandom {
        private static final long serialVersionUID = 1L;

        @Override
        public int nextInt(int bound) {
            return 0;
        }
    }
}
