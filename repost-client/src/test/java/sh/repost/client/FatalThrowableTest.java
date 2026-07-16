package sh.repost.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FatalThrowableTest {
    @Test
    void centralPredicatesMatchTheFrozenJvmContract() {
        assertFalse(FatalThrowable.isFatal(new RuntimeException()));
        assertTrue(FatalThrowable.isFatal(new AssertionError()));
        assertTrue(FatalThrowable.isFatal(new LinkageError()));
        assertFalse(FatalThrowable.isUnrecoverable(new AssertionError()));
        assertTrue(FatalThrowable.isUnrecoverable(new OutOfMemoryError()));
        assertTrue(FatalThrowable.isUnrecoverable(new ThreadDeath()));
    }
}
