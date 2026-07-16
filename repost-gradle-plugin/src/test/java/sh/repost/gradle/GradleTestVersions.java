package sh.repost.gradle;

import org.gradle.testkit.runner.GradleRunner;

/** Applies the compatibility-matrix lane selected by the CI execution owner. */
final class GradleTestVersions {
    static final String GRADLE_VERSION = "REPOST_TEST_GRADLE_VERSION";
    static final String KOTLIN_VERSION = "REPOST_TEST_KOTLIN_VERSION";

    private GradleTestVersions() {
    }

    static GradleRunner apply(GradleRunner runner) {
        String version = System.getenv(GRADLE_VERSION);
        return version == null || version.isBlank() ? runner : runner.withGradleVersion(version);
    }

    static String kotlinVersion() {
        return System.getenv(KOTLIN_VERSION);
    }
}
