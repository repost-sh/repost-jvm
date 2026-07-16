package sh.repost.gradle;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.gradle.api.GradleException;
import org.gradle.api.NamedDomainObjectCollection;
import org.gradle.api.Project;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;

final class KotlinSourceSetWiring {
    private KotlinSourceSetWiring() {
    }

    static void wire(
        Project project,
        RepostSdkExtension extension,
        TaskProvider<RepostVerifyGeneratedOutputsTask> verify
    ) {
        SourceDirectorySet kotlin = kotlinSources(project);
        kotlin.srcDir(extension.getGenerators().named("kotlinSdk")
            .flatMap(RepostGeneratorSpec::getSourceOutputDirectory));
        project.getTasks().named("compileKotlin").configure(task -> task.dependsOn(verify));
    }

    private static SourceDirectorySet kotlinSources(Project project) {
        try {
            Object kotlinExtension = project.getExtensions().getByName("kotlin");
            Method getSourceSets = kotlinExtension.getClass().getMethod("getSourceSets");
            Object sourceSets = getSourceSets.invoke(kotlinExtension);
            if (!(sourceSets instanceof NamedDomainObjectCollection)) {
                throw unsupported();
            }
            Object main = ((NamedDomainObjectCollection<?>) sourceSets).getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            Object kotlin = main.getClass().getMethod("getKotlin").invoke(main);
            if (!(kotlin instanceof SourceDirectorySet)) {
                throw unsupported();
            }
            return (SourceDirectorySet) kotlin;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new GradleException("Repost could not wire the applied Kotlin JVM source set", exception);
        }
    }

    private static GradleException unsupported() {
        return new GradleException("Repost could not wire the applied Kotlin JVM source set");
    }
}
