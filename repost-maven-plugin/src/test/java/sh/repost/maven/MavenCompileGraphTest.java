package sh.repost.maven;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Set;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import sh.repost.buildplugin.internal.SdkVersion;

final class MavenCompileGraphTest {
    @Test
    void validatesTransitiveCoreFromThePublicResolvedArtifactGraph() throws Throwable {
        Dependency kotlin = new Dependency();
        kotlin.setGroupId("sh.repost");
        kotlin.setArtifactId("repost-client-kotlin");
        kotlin.setVersion(SdkVersion.VALUE);
        MavenProject project = new MavenProject();
        MethodHandles.lookup()
            .findVirtual(MavenProject.class, "setArtifacts", MethodType.methodType(void.class, Set.class))
            .invoke(project, Set.of(
                new ResolvedArtifact("repost-client-kotlin"),
                new ResolvedArtifact("repost-client")
            ));
        project.setDependencies(List.of(kotlin));

        MavenCompileGraph graph = MavenCompileGraph.from(project);
        assertDoesNotThrow(() -> graph.validateRuntime(Set.of("kotlin")));
    }

    public static final class ResolvedArtifact {
        private final String artifactId;

        private ResolvedArtifact(String artifactId) {
            this.artifactId = artifactId;
        }

        public String getScope() { return "compile"; }
        public String getGroupId() { return "sh.repost"; }
        public String getArtifactId() { return artifactId; }
        public String getVersion() { return SdkVersion.VALUE; }
        public File getFile() { return new File(artifactId + ".jar"); }
    }
}
