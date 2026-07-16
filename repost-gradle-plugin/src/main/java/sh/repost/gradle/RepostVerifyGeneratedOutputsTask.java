package sh.repost.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import sh.repost.buildplugin.internal.BuildPluginEngine;

/** Authenticates generated trees after Gradle restores or reuses generation outputs. */
@DisableCachingByDefault(because = "verification must run after every generation or cache restoration")
public abstract class RepostVerifyGeneratedOutputsTask extends DefaultTask {
    /** Constructs the generated-output verification task. */
    public RepostVerifyGeneratedOutputsTask() {}

    /** Returns selected generator names in stable order.
     * @return generator names
     */
    @Input
    public abstract ListProperty<String> getGeneratorNames();

    /** Returns generated source trees to fingerprint before verification.
     * @return source output directories
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceOutputDirectories();

    /** Returns generated resource trees to fingerprint before verification.
     * @return resource output directories
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getResourceOutputDirectories();

    /** Returns generator-to-source-output mapping consumed by the task action.
     * @return source output paths by generator
     */
    @Internal
    public abstract MapProperty<String, String> getSourceOutputPathsByGenerator();

    /** Returns generator-to-resource-output mapping consumed by the task action.
     * @return resource output paths by generator
     */
    @Internal
    public abstract MapProperty<String, String> getResourceOutputPathsByGenerator();

    /** Authenticates every marker and generated tree without mutating outputs. */
    @TaskAction
    public final void verifyGeneratedOutputs() {
        Map<String, String> sources = getSourceOutputPathsByGenerator().get();
        Map<String, String> resources = getResourceOutputPathsByGenerator().get();
        List<BuildPluginEngine.Generator> generators = new ArrayList<>();
        for (String name : getGeneratorNames().get()) {
            String source = sources.get(name);
            String resource = resources.get(name);
            if (source == null || resource == null) {
                throw new GradleException("Repost generated output mapping is incomplete for " + name);
            }
            generators.add(new BuildPluginEngine.Generator(
                name,
                source,
                resource,
                source + "/.verification-control",
                resource + "/.verification-control"
            ));
        }
        try {
            BuildPluginEngine.verifyGeneratedOutputs(generators);
        } catch (BuildPluginEngine.EngineException | IllegalArgumentException exception) {
            throw new GradleException(exception.getMessage());
        }
    }
}
