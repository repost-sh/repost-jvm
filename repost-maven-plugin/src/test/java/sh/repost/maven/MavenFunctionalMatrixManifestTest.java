package sh.repost.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class MavenFunctionalMatrixManifestTest {
    private static final Set<String> REQUIRED_CASES = Set.of(
        "custom-package-client-output",
        "declared-environment-input",
        "missing-environment-input",
        "changed-environment-input",
        "ambient-environment-ignored",
        "java-kotlin-distinct-packages-types",
        "duplicate-fqcn-no-write",
        "multiple-clients",
        "multi-module-no-schema-two-libraries",
        "duplicate-identities-across-artifacts",
        "parallel-maven",
        "incremental-noop",
        "stale-dependency-registry-cleanup",
        "failed-generation-no-partial-tree",
        "source-position-diagnostics",
        "windows-separators-unicode-paths",
        "offline-cached-generation",
        "clean-consumer-no-installed-cli"
    );

    @Test
    void frozenMatrixHasEveryTaskTwelveCaseAndBothBindingMavenEndpoints() throws Exception {
        String manifest;
        try (InputStream input = getClass().getResourceAsStream("/maven-functional-matrix-v1.tsv")) {
            assertNotNull(input);
            manifest = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String[] lines = manifest.split("\\n");
        assertEquals("formatVersion\t1", lines[0]);
        Set<String> cases = new LinkedHashSet<>();
        Set<String> endpoints = new LinkedHashSet<>();
        for (String line : Arrays.copyOfRange(lines, 1, lines.length)) {
            String[] fields = line.split("\\t");
            if ("case".equals(fields[0])) {
                assertTrue(cases.add(fields[1]), "duplicate functional case " + fields[1]);
            } else if ("endpoint".equals(fields[0])) {
                endpoints.add(fields[1]);
            }
        }
        assertEquals(REQUIRED_CASES, cases);
        assertEquals(Set.of("3.9.0", "3.9.16"), endpoints);
        assertTrue(manifest.contains("receipt\tfull-external-generation-consumer\tTask22"));
    }

    @Test
    void compatibilityWorkflowOwnsExactMavenHelpOnlineOfflineAndNoCliSmokes() throws Exception {
        String workflow = Files.readString(repositoryRoot().resolve(
            ".github/workflows/jvm-maven-compatibility.yml"));

        assertTrue(workflow.contains("'sha512': build_tools['maven_minimum_sha512']"));
        assertTrue(workflow.contains("'sha512': build_tools['maven_current_sha512']"));
        assertTrue(workflow.contains("maven: ${{ fromJSON(needs.versions.outputs.maven) }}"));
        assertTrue(workflow.contains("MAVEN_VERSION: ${{ matrix.maven.version }}"));
        assertTrue(workflow.contains("sha512sum --check --strict"));
        assertTrue(workflow.contains(":repost-maven-plugin:check"));
        assertTrue(workflow.contains("REPOST_VERSION: ${{ needs.versions.outputs.runtime }}"));
        assertTrue(workflow.contains("sh.repost:repost-maven-plugin:\"$REPOST_VERSION\":help"));
        assertTrue(workflow.contains("-o -B -ntp"));
        assertTrue(workflow.contains("command -v repost"));
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve(".github/workflows"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new AssertionError("repository root was not found");
        }
        return current;
    }
}
