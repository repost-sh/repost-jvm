package sh.repost.client;

/** Central classification for fatal JVM throwables at extension boundaries. */
final class FatalThrowable {
    private FatalThrowable() { }

    static boolean isFatal(Throwable failure) {
        return failure instanceof Error;
    }

    static boolean isUnrecoverable(Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
    }
}
