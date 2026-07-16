package sh.repost.maven;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;

/** Closed, testable view of the Maven compile graph used by generation validation. */
final class MavenCompileGraph {
    private static final String CORE = "sh.repost:repost-client";
    private static final String KOTLIN = "sh.repost:repost-client-kotlin";
    private static final Map<String, String> ADAPTERS = adapters();
    private static final String BOM = "<dependencyManagement>\n"
        + "  <dependencies>\n"
        + "    <dependency>\n"
        + "      <groupId>sh.repost</groupId>\n"
        + "      <artifactId>repost-bom</artifactId>\n"
        + "      <version>1.0.0</version>\n"
        + "      <type>pom</type>\n"
        + "      <scope>import</scope>\n"
        + "    </dependency>\n"
        + "  </dependencies>\n"
        + "</dependencyManagement>";

    private final Map<String, String> versions;
    private final Map<String, Path> artifacts;
    private final Set<String> declared;

    private MavenCompileGraph(
        Map<String, String> versions,
        Map<String, Path> artifacts,
        Set<String> declared
    ) {
        this.versions = Map.copyOf(versions);
        this.artifacts = Map.copyOf(artifacts);
        this.declared = Set.copyOf(declared);
    }

    static MavenCompileGraph from(MavenProject project) {
        Map<String, String> versions = new LinkedHashMap<>();
        Map<String, Path> artifacts = new LinkedHashMap<>();
        Set<String> declared = new LinkedHashSet<>();
        for (Dependency dependency : project.getDependencies()) {
            if (!isCompileScope(dependency.getScope())) {
                continue;
            }
            String coordinate = dependency.getGroupId() + ":" + dependency.getArtifactId();
            declared.add(coordinate);
            versions.put(coordinate, dependency.getVersion());
        }
        try {
            for (Object artifact : resolvedArtifacts(project)) {
                String scope = invokeString(artifact, "getScope");
                if (!isCompileScope(scope)) {
                    continue;
                }
                String coordinate = invokeString(artifact, "getGroupId") + ":"
                    + invokeString(artifact, "getArtifactId");
                versions.put(coordinate, invokeString(artifact, "getVersion"));
                Object file = invoke(artifact, "getFile");
                if (file instanceof File) {
                    artifacts.put(coordinate, ((File) file).toPath().toAbsolutePath().normalize());
                }
            }
        } catch (NoClassDefFoundError unavailableInIsolatedUnitRuntime) {
            // Maven supplies maven-artifact in real goal execution; model-only tests intentionally do not.
        }
        return new MavenCompileGraph(versions, artifacts, declared);
    }

    static MavenCompileGraph testing(Map<String, Path> resolved, Set<String> declared) {
        Map<String, String> versions = new LinkedHashMap<>();
        Map<String, Path> artifacts = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : resolved.entrySet()) {
            String[] parts = entry.getKey().split(":", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("resolved Maven coordinate must be group:artifact:version");
            }
            versions.put(parts[0] + ":" + parts[1], parts[2]);
            artifacts.put(parts[0] + ":" + parts[1], entry.getValue());
        }
        return new MavenCompileGraph(versions, artifacts, declared);
    }

    Set<Path> artifactPaths() {
        return new LinkedHashSet<>(artifacts.values());
    }

    Map<Path, String> artifactLabels() {
        Map<Path, String> labels = new LinkedHashMap<>();
        artifacts.forEach((coordinate, path) ->
            labels.put(path, coordinate + ":" + versions.get(coordinate))
        );
        return labels;
    }

    String resolveIntegration(String configured) {
        String requested = configured == null ? "AUTO" : configured.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"AUTO".equals(requested) && !"NONE".equals(requested)
            && !ADAPTERS.containsValue(requested)) {
            throw new IllegalArgumentException(
                "Repost integration must be AUTO, NONE, SPRING_BOOT, or CDI"
            );
        }
        Set<String> matches = new LinkedHashSet<>();
        for (String coordinate : declared) {
            String integration = ADAPTERS.get(coordinate);
            if (integration != null) {
                matches.add(integration);
            }
        }
        if ("NONE".equals(requested)) {
            return requested;
        }
        if ("AUTO".equals(requested)) {
            if (matches.isEmpty()) {
                return "NONE";
            }
            if (matches.size() == 1) {
                return matches.iterator().next();
            }
            throw new IllegalArgumentException(
                "multiple declared Repost adapters require integration NONE, SPRING_BOOT, or CDI"
            );
        }
        if (!matches.contains(requested)) {
            throw new IllegalArgumentException(
                "integration " + requested + " requires its matching declared Repost adapter dependency"
            );
        }
        return requested;
    }

    void validateRuntime(Set<String> languages) {
        if (languages.contains("java")) {
            requireVersion(CORE);
            if (!declared.contains(CORE)) {
                List<String> declaredAdapters = new ArrayList<>();
                for (String coordinate : declared) {
                    if (ADAPTERS.containsKey(coordinate)) {
                        declaredAdapters.add(coordinate);
                    }
                }
                if (declaredAdapters.size() != 1) {
                    throw runtimeError(
                        "Java generation requires repost-client directly or transitively through exactly one declared adapter",
                        CORE
                    );
                }
            }
        }
        if (languages.contains("kotlin")) {
            requireVersion(CORE);
            requireVersion(KOTLIN);
        }
    }

    private void requireVersion(String coordinate) {
        String version = versions.get(coordinate);
        if (!"1.0.0".equals(version)) {
            throw runtimeError(
                coordinate + " must resolve to exactly 1.0.0 (found " + version + ")",
                coordinate
            );
        }
    }

    private static IllegalArgumentException runtimeError(String detail, String coordinate) {
        String artifactId = coordinate.substring(coordinate.indexOf(':') + 1);
        return new IllegalArgumentException("Repost generated-client runtime validation failed: "
            + detail + ". Import the exact Repost BOM and dependency:\n" + BOM
            + "\n<dependencies>\n"
            + "  <dependency>\n"
            + "    <groupId>sh.repost</groupId>\n"
            + "    <artifactId>" + artifactId + "</artifactId>\n"
            + "  </dependency>\n"
            + "</dependencies>");
    }

    private static boolean isCompileScope(String scope) {
        return scope == null || scope.isEmpty() || "compile".equals(scope) || "provided".equals(scope);
    }

    private static Set<?> resolvedArtifacts(MavenProject project) {
        Set<?> artifacts = project.getArtifacts();
        return artifacts == null ? Set.of() : artifacts;
    }

    private static String invokeString(Object target, String name) {
        Object value = invoke(target, name);
        return value == null ? null : value.toString();
    }

    private static Object invoke(Object target, String name) {
        try {
            Method method = target.getClass().getMethod(name);
            return method.invoke(target);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalArgumentException("Repost resolved Maven compile graph is unavailable");
        }
    }

    private static Map<String, String> adapters() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("sh.repost:repost-client-spring-boot-starter", "SPRING_BOOT");
        result.put("sh.repost:repost-client-cdi", "CDI");
        return Map.copyOf(result);
    }
}
