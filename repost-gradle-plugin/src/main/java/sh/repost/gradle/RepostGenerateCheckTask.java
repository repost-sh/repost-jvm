package sh.repost.gradle;

import javax.inject.Inject;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

/** Cacheable isolated determinism and optional checked-in-output verification task. */
@CacheableTask
public abstract class RepostGenerateCheckTask extends AbstractRepostGenerationTask {
    /** Creates a generation-check task. */
    @Inject
    public RepostGenerateCheckTask() {
    }

    /** Returns the optional checked-in marked root compared without mutation.
     * @return checked-in comparison root
     */
    @Optional
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getCheckAgainst();
}
