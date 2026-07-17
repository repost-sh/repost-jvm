package sh.repost.maven;

/** Published Maven plugin artifact version. */
final class MavenPluginVersion {
    private MavenPluginVersion() {
    }

    static String current() {
        String version = MavenPluginVersion.class.getPackage().getImplementationVersion();
        return version == null ? "1.0.0" : version;
    }
}
