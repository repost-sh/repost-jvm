package sh.repost.maven;

import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** Checks deterministic Repost SDK generation without registering temporary roots. */
@Mojo(
    name = "check",
    defaultPhase = LifecyclePhase.VERIFY,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true
)
public final class RepostCheckMojo extends AbstractRepostMojo {
    /** Creates the Maven check goal. */
    public RepostCheckMojo() {
    }

    @Override
    boolean isCheck() {
        return true;
    }
}
