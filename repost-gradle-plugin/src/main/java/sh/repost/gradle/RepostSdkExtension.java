package sh.repost.gradle;

import java.time.Duration;
import javax.inject.Inject;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/** Lazy, typed configuration for the {@code sh.repost.sdk} Gradle plugin. */
public abstract class RepostSdkExtension {
    private final NamedDomainObjectContainer<RepostGeneratorSpec> generators;

    /**
     * Creates the extension and installs provider-backed conventions without reading project inputs.
     *
     * @param objects Gradle object factory
     * @param layout project layout
     */
    @Inject
    public RepostSdkExtension(ObjectFactory objects, ProjectLayout layout) {
        getSchemaMode().convention(RepostSchemaMode.GENERATE);
        getSchemaFile().convention(layout.getProjectDirectory().file("repost/schema.repost"));
        getEnvironmentInputs().convention(java.util.Collections.emptyMap());
        getIntegration().convention(RepostIntegration.AUTO);
        getEngineVersion().convention("0.9.0");
        getExecutionTimeout().convention(Duration.ofMinutes(5));

        generators = objects.domainObjectContainer(
            RepostGeneratorSpec.class,
            name -> objects.newInstance(RepostGeneratorSpec.class, name)
        );
        generators.register("javaSdk", generator -> {
            generator.getEnabled().convention(true);
            generator.getSourceOutputDirectory().convention(
                layout.getBuildDirectory().dir("generated/sources/repost/javaSdk/java")
            );
            generator.getResourceOutputDirectory().convention(
                layout.getBuildDirectory().dir("generated/resources/repost/javaSdk")
            );
        });
        generators.register("kotlinSdk", generator -> {
            generator.getEnabled().convention(true);
            generator.getSourceOutputDirectory().convention(
                layout.getBuildDirectory().dir("generated/sources/repost/kotlinSdk/kotlin")
            );
            generator.getResourceOutputDirectory().convention(
                layout.getBuildDirectory().dir("generated/resources/repost/kotlinSdk")
            );
        });
    }

    /**
     * Returns the local-schema or aggregate-only mode.
     *
     * @return configured schema mode
     */
    public abstract Property<RepostSchemaMode> getSchemaMode();

    /**
     * Returns the local schema used in generation mode.
     *
     * @return configured schema file
     */
    public abstract RegularFileProperty getSchemaFile();

    /**
     * Returns explicit environment names and values visible to selected generators.
     *
     * @return environment inputs
     */
    public abstract MapProperty<String, String> getEnvironmentInputs();

    /**
     * Returns the generated framework-integration selection.
     *
     * @return configured integration
     */
    public abstract Property<RepostIntegration> getIntegration();

    /**
     * Returns the exact native schema-engine version.
     *
     * @return configured engine version
     */
    public abstract Property<String> getEngineVersion();

    /**
     * Returns the maximum native engine execution duration.
     *
     * @return configured timeout
     */
    public abstract Property<Duration> getExecutionTimeout();

    /**
     * Returns the optional checked-in marked root compared by the check task.
     *
     * @return comparison root
     */
    public abstract DirectoryProperty getCheckAgainst();

    /**
     * Returns per-generator dedicated output overrides.
     *
     * @return named generator container
     */
    public final NamedDomainObjectContainer<RepostGeneratorSpec> getGenerators() {
        return generators;
    }
}
