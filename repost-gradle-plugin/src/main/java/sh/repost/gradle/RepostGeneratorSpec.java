package sh.repost.gradle;

import javax.inject.Inject;
import org.gradle.api.Named;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;

/** Dedicated source and resource output overrides for one schema generator. */
public abstract class RepostGeneratorSpec implements Named {
    private final String name;

    /**
     * Creates a named generator override.
     *
     * @param name schema generator name
     */
    @Inject
    public RepostGeneratorSpec(String name) {
        this.name = name;
    }

    @Override
    public final String getName() {
        return name;
    }

    /**
     * Returns whether this generator participates in generation and verification.
     *
     * @return generator enablement property
     */
    public abstract Property<Boolean> getEnabled();

    /**
     * Returns the dedicated generated-source directory for this generator.
     *
     * @return generated-source directory property
     */
    public abstract DirectoryProperty getSourceOutputDirectory();

    /**
     * Returns the dedicated generated-resource directory for this generator.
     *
     * @return generated-resource directory property
     */
    public abstract DirectoryProperty getResourceOutputDirectory();
}
