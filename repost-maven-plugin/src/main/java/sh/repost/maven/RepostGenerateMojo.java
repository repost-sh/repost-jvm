package sh.repost.maven;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** Generates Repost Java and Kotlin SDK sources and resources. */
@Mojo(
    name = "generate",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true
)
public final class RepostGenerateMojo extends AbstractRepostMojo {
    /** Creates the Maven generate goal. */
    public RepostGenerateMojo() {
    }

    @Override
    boolean isCheck() {
        return false;
    }
}
