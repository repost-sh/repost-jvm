package sh.repost.gradle;

import javax.inject.Inject;
import org.gradle.api.tasks.CacheableTask;

/** Cacheable Repost source and resource generation task. */
@CacheableTask
public abstract class RepostGenerateTask extends AbstractRepostGenerationTask {
    /** Creates a generation task. */
    @Inject
    public RepostGenerateTask() {
    }
}
