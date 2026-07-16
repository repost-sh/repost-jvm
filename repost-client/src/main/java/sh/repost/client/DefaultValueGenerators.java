package sh.repost.client;

import java.time.Instant;
import java.util.Objects;

/** Supplies operation-snapshotted values for generated schema defaults. */
public interface DefaultValueGenerators {
    /**
     * Creates deterministic generators for tests and controlled integrations.
     *
     * @param now fixed current instant
     * @param uuid fixed valid UUIDv4 string
     * @param cuid fixed valid CUID2-shaped string
     * @return fixed generators
     */
    static DefaultValueGenerators fixed(Instant now, String uuid, String cuid) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(cuid, "cuid");
        return new DefaultValueGenerators() {
            @Override
            public Instant now() {
                return now;
            }

            @Override
            public String uuid() {
                return uuid;
            }

            @Override
            public String cuid() {
                return cuid;
            }

            @Override
            public String toString() {
                return "DefaultValueGenerators[fixed]";
            }
        };
    }

    /**
     * Returns the operation-snapshotted current instant.
     *
     * @return current instant
     */
    Instant now();

    /**
     * Returns a per-occurrence UUIDv4.
     *
     * @return UUIDv4 string
     */
    String uuid();

    /**
     * Returns a per-occurrence CUID2-shaped value.
     *
     * @return CUID string
     */
    String cuid();
}
