package sh.repost.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Uncached preflight that protects generated output roots before Gradle can restore cached outputs. */
@DisableCachingByDefault(because = "This task validates external filesystem state and has no outputs")
public abstract class RepostValidateOutputOwnershipTask extends DefaultTask implements GradleOwnedOutputs {
    /** Constructs the output-ownership preflight task. */
    public RepostValidateOutputOwnershipTask() {}

    /** @return configured schema mode */
    @Internal
    public abstract Property<RepostSchemaMode> getSchemaMode();

    /** @return configured generator names */
    @Internal
    public abstract ListProperty<String> getGeneratorNames();

    /** @return generator source roots */
    @Internal
    public abstract MapProperty<String, String> getSourceOutputPathsByGenerator();

    /** @return generator resource roots */
    @Internal
    public abstract MapProperty<String, String> getResourceOutputPathsByGenerator();

    /** @return project directory */
    @Internal
    public abstract DirectoryProperty getProjectDirectory();

    /** @return aggregate source root */
    @Internal
    public abstract DirectoryProperty getAggregateSourceOutputDirectory();

    /** @return aggregate resource root */
    @Internal
    public abstract DirectoryProperty getAggregateResourceOutputDirectory();

    /** @return build group */
    @Internal
    public abstract Property<String> getBuildGroup();

    /** @return root project name */
    @Internal
    public abstract Property<String> getBuildRootProjectName();

    /** @return project path */
    @Internal
    public abstract Property<String> getBuildProjectPath();

    /** Validates output ownership before the cacheable generation task can mutate outputs. */
    @TaskAction
    public final void validateOwnership() {
        GradleOutputOwnership.validate(this);
    }
}
